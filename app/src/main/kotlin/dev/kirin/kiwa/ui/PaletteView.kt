package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kirin.kiwa.palette.Fuzzy
import dev.kirin.kiwa.palette.PaletteItem
import dev.kirin.kiwa.palette.PaletteQuery
import dev.kirin.kiwa.trace.JsonlTrace

/**
 * コマンドパレット。**案C の芯** ── 常設バーに出していない操作は全部ここから引く。
 *
 * ```
 * ┌──────────────────────────┐
 * │ > save                   │  ← 打つ欄。IME が付く
 * │ コマンド · 3 件           │
 * ├──────────────────────────┤
 * │ 保存            sample.go │
 * │ 設定             app.…    │
 * └──────────────────────────┘
 * ```
 *
 * ## ダイアログにしない
 *
 * 引き出しと同じ層に**被せる**。ダイアログは別の窓になるので、システムバーの余白（insets）を
 * この画面とは別に自分で解く必要があり、**キーボードの着脱で骨格が組み変わる**
 * ── 案Cの「位置は変わらない」がそこだけ崩れる。
 *
 * ## ★ここの入力欄は IME を通る（本文の境界は効かない）
 *
 * `SoraImeBoundary` は Sora の `InputConnection` に掛かっているので、**この欄には効かない**。
 * そして E7 の実機で「設定のテキスト欄が IME を通って `go` が『ご』になる」を踏んでいる。
 *
 * **ただしパレットは事情が逆で、日本語を通す必要がある** ── 日本語のファイル名も
 * コマンドの名前（「保存」）も引けないと入口として使えない。だから `IME_FLAG_FORCE_ASCII` で
 * 殴らず、**変換中の文字を絞り込みへ流さない**形にした（[queryText]）。
 * 未確定のまま絞り込むと、変換候補を選ぶたびに一覧が跳ねて選べない。
 *
 * 同じ理由で、**変換中はキーを横取りしない** ── Enter も Esc も矢印も、
 * 変換中は IME のもの（E6 で本文について決めたのと同じ線）。
 */
class PaletteView(
    context: Context,
    private val trace: JsonlTrace?,
    /** モードに応じた母集団を作る。**絞り込みはこの中でやらない**（ここが受け取ってから点を付ける）。 */
    private val source: (PaletteQuery) -> List<PaletteItem>,
    private val onClosed: () -> Unit
) : FrameLayout(context) {

    /** 高さの上限を持つ入れ物。候補が増えても画面を埋め尽くさない。 */
    private class LimitedScroll(context: Context) : ScrollView(context) {
        var maxHeight: Int = Int.MAX_VALUE

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(
                widthSpec,
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            )
        }
    }

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val input = ImeAwareInput(context)
    private val modeBar = LinearLayout(context)
    private val modeButtons = LinkedHashMap<PaletteQuery.Mode, TextView>()
    private val hint = TextView(context)
    private val scroll = LimitedScroll(context)
    private val list = LinearLayout(context)

    private var palette: Palette = Palette.SUMI
    private var items: List<PaletteItem> = emptyList()
    private var selected = 0

    /** 開いている間だけ真。 */
    val isOpen: Boolean get() = visibility == VISIBLE

    /**
     * 一覧の脇に出す注記。**「全部は見えていない」を黙らせないため**にある
     * （ファイルの索引を上限で打ち切ったとき等）。
     */
    var note: String = ""
        set(value) {
            field = value
            if (isOpen) refresh()
        }

    init {
        visibility = GONE

        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(PAD, PAD, PAD, PAD)
        // 帯の外を押したら閉じる、が**帯の中を押しても閉じない**ようにする。
        panel.isClickable = true

        input.inputType = InputType.TYPE_CLASS_TEXT
        // ソフトキーボードの改行を「実行」にする。**日本語を通すので FORCE_ASCII は付けない。**
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.textSize = 18f
        input.setPadding(PAD, PAD, PAD, PAD)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        input.setOnEditorActionListener { _, _, _ -> chooseSelected(); true }
        // 移動と決定と取り消しは [consumeKey] が引き受ける（口は1つ）。ここには置かない。
        panel.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // **モードを押しても変えられるようにする（IME を通らない道）。**
        // 実機で `>` が全角の未確定になったので、prefix だけが入口だと
        // 日本語 IME の状態によってはコマンドへ辿り着けない（2026-09-07）。
        modeBar.orientation = LinearLayout.HORIZONTAL
        modeBar.gravity = Gravity.CENTER_VERTICAL
        modeBar.setPadding(PAD, 0, PAD, PAD)
        for (mode in PaletteQuery.Mode.entries) {
            val button = TextView(context).apply {
                text = (mode.prefix + " " + mode.hint).trim()
                textSize = 13f
                setPadding(PAD * 2, PAD, PAD * 2, PAD)
                isClickable = true
                setOnClickListener { switchMode(mode) }
            }
            modeButtons[mode] = button
            modeBar.addView(button)
        }

        hint.textSize = 12f
        hint.gravity = Gravity.END
        modeBar.addView(
            hint,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        panel.addView(
            modeBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        list.orientation = LinearLayout.VERTICAL
        scroll.isFillViewport = true
        scroll.addView(
            list,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addView(
            panel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )
    }

    // ------------------------------------------------------------------
    // 開け閉め
    // ------------------------------------------------------------------

    /**
     * 開く。[prefix] を入れた状態から始められる（`:` なら行モード）。
     * **既に開いていれば入れ直す** ── コマンドから行モードへ移るのがこの道。
     */
    fun open(prefix: String) {
        val metrics = resources.displayMetrics
        panel.layoutParams = (panel.layoutParams as LayoutParams).apply {
            width = minOf((metrics.widthPixels * 0.62f).toInt(), MAX_WIDTH)
            topMargin = TOP_MARGIN
        }
        scroll.maxHeight = (metrics.heightPixels * 0.55f).toInt()

        visibility = VISIBLE
        input.setText(prefix)
        input.setSelection(prefix.length)
        input.requestFocus()
        // requestFocus と同じ回では窓がまだ受け取れていないことがある。
        post {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        refresh()
        trace?.event("palette", "open", "prefix", prefix)
    }

    /**
     * この画面が引き受けるキーならここで処理して true。**画面の一番外側から呼ぶ**
     * （`MainActivity.dispatchKeyEvent`）。
     *
     * **欄のフォーカスを当てにしない。** 実機 G で、↑↓ と Enter は欄に届くのに
     * **Esc だけ誰にも届かない**状態になった（`onKeyPreIme` はフォーカスされた View に
     * しか来ない）。開いている間はこの画面が画面全体を覆っているので、
     * 迷わず所有してよい ── 検索の帯が「最後に触った場所」で決めているのと違い、
     * ここは本文と取り合いにならない。
     *
     * **変換中は渡さない** ── Enter は確定に、Esc は取り消しに使われる。
     * 文字キーは1つも見ない（欄への入力は素通し）。
     */
    fun consumeKey(event: KeyEvent): Boolean {
        if (visibility != VISIBLE) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (input.isComposing()) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(-1); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { chooseSelected(); true }
            KeyEvent.KEYCODE_ESCAPE -> { close(); true }
            else -> false
        }
    }

    fun close() {
        if (!isOpen) return
        visibility = GONE
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(windowToken, 0)
        input.setText("")
        items = emptyList()
        list.removeAllViews()
        trace?.event("palette", "close")
        onClosed()
    }

    /** 開いていれば引き直す。**索引は後から届く**ので、届いた時に一覧へ流し込む。 */
    fun refreshIfOpen() {
        if (isOpen) refresh()
    }

    // ------------------------------------------------------------------
    // 絞り込み
    // ------------------------------------------------------------------

    /**
     * 絞り込みに使う文字列。**変換中の部分は落とす**（扱いは [ImeAwareInput] が持つ）。
     *
     * 未確定のまま流すと、変換候補を選ぶたびに一覧が跳ねて選べない
     * （「ほぞん」と打つ途中の「ほ」「ほz」で毎回引き直すことになる）。
     * 確定した分だけで引いて、確定した瞬間に一覧が動く形にする。
     */
    private fun queryText(): String = input.settledText()

    /**
     * 今引くもの。**モードは生の文字から、語は確定した分から**。
     *
     * prefix そのものが変換中でも「何を引こうとしているか」は決まっている ──
     * 実機では `>` が全角の未確定として入り、確定するまでファイルモードのままだった。
     * 語の側は逆に、確定していない文字で絞ると打つたびに一覧が跳ねる。
     *
     * **行モードだけは語も生から読む。** 候補が1件しかないので跳ねようが無く、
     * 待たせるぶんだけ手数が増える ── 実機では `300` が全角の未確定「３００」で入り、
     * 確定するまで「当たらない」と出ていた。
     */
    private fun currentQuery(): PaletteQuery {
        val raw = PaletteQuery.parse(input.rawText())
        if (raw.mode == PaletteQuery.Mode.LINE) return raw
        return PaletteQuery(raw.mode, PaletteQuery.parse(queryText()).term)
    }

    private fun refresh() {
        val raw = input.rawText()
        val used = queryText()
        val query = currentQuery()
        val pool = source(query)
        items = if (query.mode == PaletteQuery.Mode.LINE) {
            pool
        } else {
            Fuzzy.rank(pool, query.term, MAX_ITEMS) { it.searchText }
        }
        selected = 0
        renderModes(query.mode)
        renderHint(query, pool.size)
        renderList()
        trace?.event(
            "palette", "query",
            "raw", raw,
            "used", used,
            "composing", input.isComposing(),
            "mode", query.mode.name,
            "hits", items.size
        )
    }

    private fun renderModes(current: PaletteQuery.Mode) {
        for ((mode, button) in modeButtons) {
            val on = mode == current
            button.setBackgroundColor(if (on) palette.button else palette.toolbar)
            button.setTextColor(if (on) palette.text else palette.dim)
            button.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun renderHint(query: PaletteQuery, pool: Int) {
        hint.text = buildString {
            append(items.size).append(" 件")
            if (items.size < pool) append("／").append(pool)
            if (note.isNotEmpty()) append("　").append(note)
            if (input.isComposing()) append("　変換中")
        }
    }

    /**
     * モードを押して変える。**打った語は残す** ── 引くものを間違えただけで
     * 打ち直しになると、入口が1つである意味が薄れる。
     *
     * **行モードへ移るときだけ数字以外を捨てる。** 行は数字しか受けないので、
     * 文字を持ち込むと必ず「当たらない」になる ── 実機で `保存` と打った後に
     * 行へ移り、`:保存３００` になって候補が出なかった（2026-09-07）。
     */
    private fun switchMode(mode: PaletteQuery.Mode) {
        val kept = PaletteQuery.parse(queryText()).term
        val term = if (mode == PaletteQuery.Mode.LINE) kept.filter { it.isDigit() } else kept
        input.setText(mode.prefix + term)
        input.setSelection(input.text?.length ?: 0)
        trace?.event("palette", "mode", "to", mode.name)
    }

    private fun renderList() {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(emptyRow())
            return
        }
        for ((index, item) in items.withIndex()) list.addView(row(item, index))
    }

    private fun emptyRow(): View = TextView(context).apply {
        text = "当たらない"
        textSize = 14f
        setTextColor(palette.dim)
        setPadding(PAD * 2, ROW_PAD, PAD * 2, ROW_PAD)
    }

    private fun row(item: PaletteItem, index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PAD * 2, ROW_PAD, PAD * 2, ROW_PAD)
            isClickable = true
            // 行全体を当たり判定にする（E7 の実機で踏んだ②と同じ話）。
            setOnClickListener { choose(item) }
            if (index == selected) setBackgroundColor(palette.button)
        }
        val title = TextView(context).apply {
            text = item.title
            textSize = 16f
            // **押せないものは灰色で出す。消さない** ── 消すと「保存はどこ？」になる。
            setTextColor(if (item.enabled) palette.text else palette.dim)
            if (index == selected) setTypeface(null, Typeface.BOLD)
        }
        val detail = TextView(context).apply {
            text = item.detail
            textSize = 12f
            setTextColor(palette.dim)
            maxLines = 1
            gravity = Gravity.END
            // 道は右側（ファイル名に近い方）が効くので、削るなら頭から。
            ellipsize = TextUtils.TruncateAt.START
        }
        row.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(
            detail,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = PAD * 2 }
        )
        return row
    }

    // ------------------------------------------------------------------
    // 選ぶ
    // ------------------------------------------------------------------

    private fun move(delta: Int) {
        if (items.isEmpty()) return
        selected = (selected + delta).coerceIn(0, items.size - 1)
        renderList()
        // 選んだ行が隠れないように寄せる。
        list.getChildAt(selected)?.let { child -> scroll.post { scroll.smoothScrollTo(0, child.top) } }
    }

    private fun chooseSelected() {
        items.getOrNull(selected)?.let { choose(it) }
    }

    /** **閉じてから実処理を呼ぶ。** 表のコマンドにはダイアログを出すものがある。 */
    private fun choose(item: PaletteItem) {
        if (!item.enabled) return
        trace?.event("palette", "choose", "title", item.title)
        close()
        item.run()
    }

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    fun apply(palette: Palette) {
        this.palette = palette
        scrim.setBackgroundColor(SCRIM)
        panel.setBackgroundColor(palette.toolbar)
        input.setBackgroundColor(palette.button)
        input.setTextColor(palette.text)
        input.setHintTextColor(palette.dim)
        hint.setTextColor(palette.dim)
        modeBar.setBackgroundColor(palette.toolbar)
        scroll.setBackgroundColor(palette.toolbar)
        renderModes(currentQuery().mode)
        if (isOpen) renderList()
    }

    private companion object {
        const val PAD = 12
        const val ROW_PAD = 18

        /** 帯の幅の上限（px）。広い画面で端から端まで伸ばすと、目が横に泳ぐ。 */
        const val MAX_WIDTH = 1500

        const val TOP_MARGIN = 60

        /** 並べる上限。これ以上は打って絞る方が速い。 */
        const val MAX_ITEMS = 60

        /** 後ろを暗くする幕。 */
        const val SCRIM = 0x99000000.toInt()
    }
}
