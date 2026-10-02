package dev.kirin.kiwa.symbols

import android.view.KeyEvent

/**
 * 記号キー列に載るキーの表（E12）。
 *
 * ## 「記号の列」ではない
 *
 * Acode の `quickTools` 49個を数えたら中身が3種類混ざっていた ── **修飾キー**（Ctrl / Shift）・
 * **生キー**（Tab / Esc / 矢印）・**コマンド**（保存 / 検索 / 戻す / 進む）・記号。
 * **記号だけ並べると Esc も Ctrl+→ も打てないまま**になるので、こちらも同じ4種類にする。
 *
 * ## 修飾キーが効くのは「この列の中の生キー」だけ
 *
 * ソフトキーボードの文字は `InputConnection.commitText` で届くので、
 * **アプリ側からは修飾を貼り付けようがない**（キーイベントではないものに Ctrl は掛からない）。
 * だから Ctrl / Shift が効くのは、**この列自身が投げる生キー**に限る ──
 * Ctrl+→ で単語単位、Shift+→ で選択、といった移動系がここの本題で、
 * Ctrl+S のような組は[コマンド][SymbolKind.COMMAND]の側が持つ。
 *
 * ## Android を呼ばない
 *
 * ここで参照している `KeyEvent.KEYCODE_*` は `int` の定数（値そのもの）で、
 * Android のメソッドは1つも呼んでいない。**だから実機を出さずに全部試験できる。**
 */
enum class SymbolKind(val label: String) {
    /** 次に押した[生キー][RAW]へ貼り付く。Ctrl / Shift。 */
    MODIFIER("修飾"),

    /** キーイベントをそのままエディタへ投げる。Tab / Esc / 矢印など。 */
    RAW("キー"),

    /** 操作の表（`Commands.kt`）を引く。**この列も表からしか生えない**（E10a）。 */
    COMMAND("コマンド"),

    /** 文字を入れる。 */
    TEXT("記号")
}

/** 貼り付く修飾キー。 */
enum class Modifier { CTRL, SHIFT }

/**
 * 列に載るキー1つ。
 *
 * @param id 設定に書く名前。**変えない** ── `EditorSettings.symbolRowKeys` に保存される
 * @param label 画面に出す文字。[SymbolKind.COMMAND] は表の `onBar` と同じものを控えとして持つ
 */
class SymbolKey internal constructor(
    val id: String,
    val label: String,
    val kind: SymbolKind,
    /** [SymbolKind.RAW] のときのキーコード。それ以外は 0。 */
    val keyCode: Int = 0,
    /** [SymbolKind.MODIFIER] のときの種類。 */
    val modifier: Modifier? = null,
    /** [SymbolKind.COMMAND] のときのコマンド id。 */
    val commandId: String? = null,
    /** [SymbolKind.TEXT] のときに入れる文字。 */
    val text: String? = null
)

object SymbolKeys {

    // ------------------------------------------------------------------
    // 載せられるもの、全部
    // ------------------------------------------------------------------

    private fun modifier(id: String, label: String, modifier: Modifier) =
        SymbolKey("mod.$id", label, SymbolKind.MODIFIER, modifier = modifier)

    private fun raw(id: String, label: String, keyCode: Int) =
        SymbolKey("raw.$id", label, SymbolKind.RAW, keyCode = keyCode)

    private fun command(commandId: String, label: String) =
        SymbolKey("cmd.$commandId", label, SymbolKind.COMMAND, commandId = commandId)

    private fun text(text: String) =
        SymbolKey("text.$text", text, SymbolKind.TEXT, text = text)

    /** 記号として出せる文字。**並びの既定に入っていないものも選べる。** */
    private val SYMBOLS = listOf(
        "{", "}", "(", ")", "[", "]", "<", ">",
        "=", ";", ":", ",", ".", "\"", "'", "`",
        "|", "&", "!", "?", "+", "-", "*", "/", "\\",
        "_", "#", "@", "$", "%", "^", "~"
    )

    /**
     * 選べるキー、全部。**並びの既定は [DEFAULT] が別に持つ** ──
     * ここは「何が選べるか」の一覧で、順番の意味は無い。
     */
    val ALL: List<SymbolKey> = buildList {
        add(modifier("ctrl", "Ctrl", Modifier.CTRL))
        add(modifier("shift", "Shift", Modifier.SHIFT))

        add(raw("tab", "Tab", KeyEvent.KEYCODE_TAB))
        add(raw("esc", "Esc", KeyEvent.KEYCODE_ESCAPE))
        add(raw("left", "←", KeyEvent.KEYCODE_DPAD_LEFT))
        add(raw("right", "→", KeyEvent.KEYCODE_DPAD_RIGHT))
        add(raw("up", "↑", KeyEvent.KEYCODE_DPAD_UP))
        add(raw("down", "↓", KeyEvent.KEYCODE_DPAD_DOWN))
        add(raw("home", "Home", KeyEvent.KEYCODE_MOVE_HOME))
        add(raw("end", "End", KeyEvent.KEYCODE_MOVE_END))
        add(raw("pageUp", "PgUp", KeyEvent.KEYCODE_PAGE_UP))
        add(raw("pageDown", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN))
        add(raw("del", "Del", KeyEvent.KEYCODE_FORWARD_DEL))

        // **画面に出すのは表の `onBar`**。ここに書くのは表を引けない場所（設定画面）用の控えで、
        // **`onBar` と同じ文字にしてある**（`SymbolKeysTest` が機械で見ている）──
        // 違う文字を書くと、設定で選んだものと列に出るものが別の名前になる。
        add(command("file.save", "保存"))
        add(command("search.find", "検索"))
        add(command("edit.undo", "戻す"))
        add(command("edit.redo", "進む"))
        add(command("edit.comment", "//"))
        add(command("edit.indent", "→|"))
        add(command("edit.outdent", "|←"))
        add(command("edit.moveLineUp", "⇧行"))
        add(command("edit.moveLineDown", "⇩行"))
        add(command("view.palette", "⌘"))

        for (symbol in SYMBOLS) add(text(symbol))
    }

    private val byId: Map<String, SymbolKey> = ALL.associateBy { it.id }

    /**
     * 既定の並び。
     * 修飾 → 生キー → コマンド → 記号の順で、押す頻度ではなく**種類でまとまっている** ──
     * 指で探すとき、隣が同じ種類だと目で追える。
     */
    val DEFAULT: List<String> = listOf(
        "mod.ctrl", "mod.shift",
        "raw.tab", "raw.esc", "raw.left", "raw.right", "raw.up", "raw.down",
        "cmd.file.save", "cmd.search.find", "cmd.edit.undo", "cmd.edit.redo",
        "text.{", "text.}", "text.(", "text.)", "text.[", "text.]",
        "text.=", "text.;", "text.\"", "text.|"
    )

    fun of(id: String): SymbolKey? = byId[id]

    /**
     * 設定に入っている id の並びをキーの並びへ直す。
     *
     * **知らない id は黙って落とす。** 設定の JSON は手でも直せるし、
     * 版が上がってキーが消えることもある ── そこで落ちるより、その1つを出さない方がいい。
     */
    fun parse(ids: List<String>): List<SymbolKey> = ids.mapNotNull { byId[it] }

    /**
     * 選んだ id を **[ALL] の順に**並べ直す。設定画面の選択（順番を持たない）から
     * 並びを作るときに使う。
     */
    fun inCanonicalOrder(ids: Collection<String>): List<String> =
        ALL.map { it.id }.filter { it in ids }
}
