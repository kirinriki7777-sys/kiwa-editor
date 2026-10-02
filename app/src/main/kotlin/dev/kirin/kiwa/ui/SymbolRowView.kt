package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.kirin.kiwa.command.Commands
import dev.kirin.kiwa.symbols.Modifier
import dev.kirin.kiwa.symbols.SymbolKey
import dev.kirin.kiwa.symbols.SymbolKeys
import dev.kirin.kiwa.symbols.SymbolKind

/**
 * 記号キー列（E12）。**1行**（Acode は2行だが、ソフトキーボードが出ると縦が半分消えるので、
 * 2行にすると本文が3〜4行しか残らない）。
 *
 * ## 押しても本文からフォーカスを奪わない
 *
 * 奪うと IME の結び先が変わり、変換中なら未確定文字列がそこで切れる。
 * ボタンは [TextView] で `focusable = false` にしてある ──
 * 触っても本文が focus を持ったままなので、IME は結ばれ続ける。
 *
 * ## 変換中は列ごと黙る
 *
 * 未確定文字列を抱えている間、この列は**押しても何もしない**（[setEnabledForComposing]）。
 * エンジンのキー処理を変換中に黙らせているのと同じ線（`ImeBoundary` の責務3）で、
 * **この列は責務3より前に居る**ので、同じ約束を自分でも持つ必要がある。
 *
 * **黙るときは見た目も変える。** 見た目が同じまま効かないと、
 * 「押したのに何も起きない」と「そもそも押せていない」が区別できない。
 */
class SymbolRowView(
    context: Context,
    private val onRawKey: (SymbolKey, ctrl: Boolean, shift: Boolean) -> Unit,
    private val onText: (SymbolKey) -> Unit,
    private val onCommand: (SymbolKey) -> Unit,
    /**
     * 押されたこと全部の合図。**記録は押すたびに1行**（動いた押しも、動かなかった押しも）。
     *
     * 分けて記録すると、**修飾を貼ってから記号を押した**ときのように
     * 「押したのに何も起きない」が無記録になる ── そこが一番読みたいところなのに。
     *
     * @param note 生キーなら効いた修飾（`ctrl` / `shift` / `ctrl+shift`）、
     *        修飾キーなら `armed` / `disarmed`、効かなかったときは理由
     */
    private val onPress: (SymbolKey, handled: Boolean, note: String) -> Unit
) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val buttons = LinkedHashMap<String, TextView>()

    private var palette: Palette = Palette.SUMI
    private var keys: List<SymbolKey> = emptyList()
    private var commands: Commands? = null

    /** 次の生キーに貼り付く修飾。押すたびに入れ替わり、生キーを1つ投げたら消える。 */
    private val armed = LinkedHashSet<Modifier>()

    /** 変換中。 */
    private var composing = false

    /** 本文がフォーカスを持っているか。検索欄やパレットに移っている間は false。 */
    private var focused = true

    /** 押して効く状態か。 */
    private val live: Boolean get() = !composing && focused

    /** 効かない理由。記録に残すために持つ ── 「効かなかった」を無記録にしない。 */
    private val deadReason: String get() = if (composing) "composing" else "unfocused"

    init {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        addView(
            row,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    /**
     * 並びを入れ替える。コマンドのラベルはここで表から埋める。
     *
     * **同じ並びなら何もしない。** この口は設定を入れる3箇所（起動 / 設定変更 / ファイルを開く）から
     * 呼ばれるので、素直に組み直すとタブを切り替えるたびにボタンを作り直すことになる。
     */
    fun setKeys(ids: List<String>, commands: Commands) {
        if (this.commands === commands && keys.map { it.id } == ids) return
        this.keys = SymbolKeys.parse(ids)
        this.commands = commands
        armed.clear()
        rebuild()
    }

    fun apply(palette: Palette) {
        this.palette = palette
        setBackgroundColor(palette.toolbar)
        render()
    }

    /**
     * 変換中かどうかを入れる。**毎回の打鍵で呼ばれる**ので、変わったときだけ描き直す。
     */
    fun setComposing(composing: Boolean) {
        if (composing == this.composing) return
        this.composing = composing
        afterStateChange()
    }

    /**
     * 本文がフォーカスを持っているかを入れる。
     *
     * **持っていない間は効かせない。** 検索欄やパレットに文字を打っている最中に
     * `{` を押したら、本文でなくその欄へ入ってほしい ── けれどこの列が投げる先は本文で固定なので、
     * 効かせると**見ている場所と入る場所が食い違う**。
     */
    fun setFocused(focused: Boolean) {
        if (focused == this.focused) return
        this.focused = focused
        afterStateChange()
    }

    private fun afterStateChange() {
        if (!live) armed.clear()
        render()
    }

    // ------------------------------------------------------------------

    private fun rebuild() {
        row.removeAllViews()
        buttons.clear()
        for (key in keys) {
            val view = TextView(context)
            view.text = labelOf(key)
            view.textSize = 15f
            view.gravity = Gravity.CENTER
            view.minWidth = CHIP_WIDTH
            // 指で押す画面なので、文字の幅だけを当たり判定にしない（E7/E8 の実機で踏んだ②）。
            view.setPadding(PAD, PAD, PAD, PAD)
            view.isClickable = true
            // **フォーカスを取らない。** 取ると IME の結び先が変わる。
            view.isFocusable = false
            view.contentDescription = descriptionOf(key)
            view.setOnClickListener { press(key) }
            buttons[key.id] = view
            row.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = PAD / 2; marginEnd = PAD / 2 }
            )
        }
        render()
    }

    private fun labelOf(key: SymbolKey): String {
        if (key.kind != SymbolKind.COMMAND) return key.label
        val id = key.commandId ?: return key.label
        // **表が引ければ表の名前を出す。** 引けないのは debug 限定のコマンドを
        // release で並べた場合で、そのときは控えのラベルで出す。
        return commands?.find(id)?.onBar ?: key.label
    }

    private fun descriptionOf(key: SymbolKey): String = when (key.kind) {
        SymbolKind.MODIFIER -> "${key.label}（次のキーに掛かる）"
        SymbolKind.RAW -> key.label
        SymbolKind.COMMAND -> commands?.find(key.commandId ?: "")?.label ?: key.label
        SymbolKind.TEXT -> "${key.text} を入れる"
    }

    private fun press(key: SymbolKey) {
        if (!live) {
            onPress(key, false, deadReason)
            return
        }
        when (key.kind) {
            SymbolKind.MODIFIER -> {
                val modifier = key.modifier ?: return
                val armedNow = !armed.remove(modifier)
                if (armedNow) armed.add(modifier)
                render()
                onPress(key, true, if (armedNow) "armed" else "disarmed")
            }
            SymbolKind.RAW -> {
                val ctrl = Modifier.CTRL in armed
                val shift = Modifier.SHIFT in armed
                // **投げる前に外す。** 投げた先で例外が出ても貼り付いたままにならない。
                armed.clear()
                render()
                onPress(key, true, modifierNote(ctrl, shift))
                onRawKey(key, ctrl, shift)
            }
            // **修飾は文字とコマンドには掛からない**（掛ける先がキーイベントしか無い）。
            // 押されたら外す ── 貼り付いたまま残ると、次の生キーに意図しない修飾が乗る。
            // **外したことは記録に出す**（`dropped` 付き）── 黙って消すと
            // 「Ctrl を押したのに効かなかった」が跡形もなくなる。
            SymbolKind.TEXT -> {
                val note = droppedNote()
                clearArmed()
                onPress(key, true, note)
                onText(key)
            }
            SymbolKind.COMMAND -> {
                val note = droppedNote()
                clearArmed()
                onPress(key, true, note)
                onCommand(key)
            }
        }
    }

    private fun modifierNote(ctrl: Boolean, shift: Boolean): String = when {
        ctrl && shift -> "ctrl+shift"
        ctrl -> "ctrl"
        shift -> "shift"
        else -> ""
    }

    /** 貼ってあった修飾を捨てるときの覚え書き。何も貼っていなければ空。 */
    private fun droppedNote(): String {
        if (armed.isEmpty()) return ""
        return "dropped:" + modifierNote(Modifier.CTRL in armed, Modifier.SHIFT in armed)
    }

    private fun clearArmed() {
        if (armed.isEmpty()) return
        armed.clear()
        render()
    }

    private fun render() {
        setBackgroundColor(palette.toolbar)
        for (key in keys) {
            val view = buttons[key.id] ?: continue
            val on = key.kind == SymbolKind.MODIFIER && key.modifier in armed
            view.setBackgroundColor(if (on) palette.text else palette.button)
            view.setTextColor(
                when {
                    !live -> palette.dim
                    on -> palette.toolbar
                    else -> palette.text
                }
            )
            view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            view.alpha = if (live) 1f else DIMMED
        }
    }

    companion object {
        private const val PAD = 12

        /** 記号1つのボタンでも指で押せる幅（`SearchBarView` と同じ）。 */
        private const val CHIP_WIDTH = 88

        /**
         * 押しても効かないときの薄さ。
         *
         * **常設バーもここを引く**（E13）── 「今は効かない」を場所によって違う濃さで出すと、
         * その差に意味があるのかを読む人が探すことになる。
         */
        const val DIMMED = 0.45f
    }
}
