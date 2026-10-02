package dev.kirin.kiwa.command

/**
 * コマンドの表。**画面に出る操作の口はここからしか生えない**（E10 / 案C の芯）。
 *
 * ## なぜ表を1本にするのか
 *
 * 案Cは「常設バーは変わらず、増えた機能はパレットへ入る」形なので、
 * **同じ操作が常設バーとパレットの2箇所から出る**。そこを別々に配線すると、
 * バーの「保存」とパレットの「保存」が別の実装を指せてしまう ──
 * 設定で `applyTo` を1本にしたのと同じ問題が、今度は操作の側で出る（E8）。
 *
 * だから **メニューバー / パレット / 記号キー列（E12）は全部この表を引く**
 * （2026-09-29 に常設バーと ⋯ をメニューバーへ置き換えた。案A）。
 * 表に無いものは画面に出ないし、表に足せば全部の入口から引ける。
 *
 * ## 「まだ作っていない」を黙って隠さない
 *
 * 層①には E11 で作るものが残っている。**それも [planned] として表に載せる** ──
 * 載せないと「作ったのに表へ足し忘れた」と「まだ作っていない」が区別できず、
 * `tools/check_commands_table.py` が両方を同じ「表に無い」として見ることになる。
 * planned のコマンドは押すと「まだ作っていない」と出るだけで、実処理を持たない。
 */
class Commands private constructor(val all: List<Command>) {

    private val byId: Map<String, Command> = all.associateBy { it.id }

    operator fun get(id: String): Command =
        byId[id] ?: throw IllegalArgumentException("知らないコマンド: $id")

    fun find(id: String): Command? = byId[id]

    /** パレットに出す並び。**今できないもの（available=false）も出す** ── 押せないだけ。 */
    fun forPalette(): List<Command> = all

    /**
     * メニューバー（案A）に出す並び。**分類ごとに1つのメニュー**で、
     * 分類の順は [CommandGroup] の並び、中の順は表に書いた順。
     *
     * **全部のコマンドがどれか1つのメニューに出る** ── 前の常設バーと ⋯ は
     * 載せる id を手で並べていたので、表に足しても載せ忘れれば画面のどこにも出なかった
     * （パレットで名前を打てば出るが、名前を知らなければ辿り着けない）。
     */
    fun menus(): List<Pair<CommandGroup, List<Command>>> =
        CommandGroup.entries.mapNotNull { group ->
            all.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }

    companion object {
        /**
         * 表を組む。**id は変えない** ── 記号キー列の設定（`cmd.<id>`）と
         * `tools/tables/commands.md` の表が id で結ばれている。
         */
        fun build(host: CommandHost): Commands {
            val out = ArrayList<Command>()

            fun command(
                id: String,
                label: String,
                group: CommandGroup,
                barLabel: String? = null,
                detail: () -> String = { "" },
                available: () -> Boolean = { true },
                run: () -> Unit
            ) {
                out.add(Command(id, label, group, barLabel, detail, available, run, planned = false))
            }

            /** まだ作っていないもの。押すと「まだ作っていない」と出る。 */
            fun planned(
                id: String,
                label: String,
                group: CommandGroup,
                barLabel: String? = null,
                detail: String = ""
            ) {
                out.add(
                    Command(
                        id, label, group, barLabel, { detail }, { true },
                        { host.notYet(label) }, planned = true
                    )
                )
            }

            // ---- ファイル ----
            // **無題のタブでも押せる**（U2）── 保存先が無ければ名前を訊く（`file.saveAs` と同じ画面）。
            // 押せない顔にしておくと、無題のタブに書いたものを保存する道が画面から消える。
            command("file.save", "保存", CommandGroup.FILE,
                detail = { host.activeFileName() ?: "（無題）" }) { host.save() }
            command("file.saveAs", "名前を付けて保存", CommandGroup.FILE,
                detail = { host.activeFileName() ?: "（無題）" }) { host.saveAs() }
            command("file.newTab", "新しいタブ", CommandGroup.FILE) { host.newTab() }
            command("file.close", "タブを閉じる", CommandGroup.FILE,
                detail = { host.activeFileName() ?: "（無題）" }) { host.closeActiveTab() }
            // **空のタブ（`file.newTab`）とは別物** ── こちらは実体を作るので保存先が決まる。
            command("file.new", "新しいファイル", CommandGroup.FILE) { host.newFile() }
            // 開き直す / 別の文字コードで保存する（2026-10-02）。**状態表示の文字コードを押しても同じこれが走る。**
            // 無題のタブは読み直すバイトも保存先も無いので押せない（保存すれば設定の既定で書かれる）。
            command("file.encoding", "文字コード", CommandGroup.FILE,
                detail = { "今: ${host.charsetLabel()}" },
                available = { host.activeFileName() != null }) { host.chooseEncoding() }

            // ---- 編集 ----
            command("edit.undo", "戻す", CommandGroup.EDIT,
                available = { host.canUndo() }) { host.undo() }
            command("edit.redo", "進む", CommandGroup.EDIT,
                available = { host.canRedo() }) { host.redo() }
            // ---- 移動 ----
            // **行移動の実装はパレットの `:` が持つ。** ここが別に持つと、
            // 同じ「行へ飛ぶ」が2つの実装を指せる（表を1本にしたのと同じ理由）。
            // **id は `edit.goto` のまま**（`tools/tables/commands.md` と id で結ばれている）。
            // 分類だけ移動へ移した ── パレットで「移動」を開いた人が行ジャンプを探す場所がそこだから。
            command("edit.goto", "行へ移動", CommandGroup.NAV) { host.openPaletteForLine() }
            command("nav.back", "元の場所へ", CommandGroup.NAV, barLabel = "元へ",
                available = { host.canGoBack() }) { host.goBack() }
            command("nav.forward", "次の場所へ", CommandGroup.NAV,
                available = { host.canGoForward() }) { host.goForward() }
            command("edit.comment", "コメントの切り替え", CommandGroup.EDIT, barLabel = "//") { host.toggleComment() }
            command("edit.indent", "字下げする", CommandGroup.EDIT, barLabel = "→|") { host.indent() }
            command("edit.outdent", "字下げを戻す", CommandGroup.EDIT, barLabel = "|←") { host.outdent() }
            command("edit.moveLineUp", "行を上へ", CommandGroup.EDIT, barLabel = "⇧行") { host.moveLineUp() }
            command("edit.moveLineDown", "行を下へ", CommandGroup.EDIT, barLabel = "⇩行") { host.moveLineDown() }

            // ---- 検索 ----
            // **探す処理はここにも画面にも無い**（エンジンの検索を包んだものを操作するだけ）。
            command("search.find", "検索・置換", CommandGroup.SEARCH, barLabel = "検索") { host.openSearch() }
            // **帯（`search.find`）とは別の画面。** 本文の検索は「今の1件」を見せれば足りるが、
            // フォルダ全体はファイルをまたいで何十件も出るので、一覧を置く面が要る。
            command("search.project", "フォルダ全体を検索", CommandGroup.SEARCH,
                detail = { host.searchRootName() }) { host.openProjectSearch() }

            // ---- 表示 ----
            command("view.drawer", "ファイルツリー", CommandGroup.VIEW, barLabel = "☰",
                detail = { if (host.drawerOpen()) "開いている" else "閉じている" }) { host.toggleDrawer() }
            command("view.wrap", "折り返し", CommandGroup.VIEW,
                detail = { if (host.wordWrap()) "今: 折り返す" else "今: 折り返さない" }) { host.toggleWrap() }
            // **このタブだけ**の言語（U3）。設定の「拡張子 → 言語」とは別物で、タブを閉じれば消える。
            command("view.language", "言語を選ぶ", CommandGroup.VIEW,
                detail = { "今: ${host.languageLabel()}" }) { host.chooseLanguage() }
            // **入口も表から生やす。** バーの欄が表を通さずにパレットを開くと、
            // 「バーからは開くのに >palette では開かない」が生まれる。
            command("view.palette", "コマンドパレット", CommandGroup.VIEW, barLabel = "⌘",
                detail = { "ファイル / コマンド / 行" }) { host.openPalette() }

            // ---- アプリ ----
            command("app.settings", "設定", CommandGroup.APP) { host.openSettings() }

            // ---- 計測 ----
            // **debug ビルドでだけ表に載る。** available=false で出すのではなく
            // 載せない ── release では計測そのものが無いので、灰色で並べる意味が無い。
            if (host.hasTrace()) {
                command("debug.ime", "IME の状態", CommandGroup.DEBUG) { host.showImeState() }
                command("debug.trace", "計測", CommandGroup.DEBUG) { host.showTraceMenu() }
            }

            return Commands(out)
        }
    }
}

/** コマンドの分類。パレットの見出しと、メニューバーのメニュー（案A）に使う。並びがメニューの順。 */
enum class CommandGroup(val label: String) {
    FILE("ファイル"),
    EDIT("編集"),
    NAV("移動"),
    SEARCH("検索"),
    VIEW("表示"),
    APP("アプリ"),
    DEBUG("計測")
}

/**
 * コマンド1本。
 *
 * [label] は静的、[detail] と [available] は**引くたびに評価する** ──
 * 「折り返し（今: 折り返す）」のように状態を出すものがあり、
 * 表を作り直さずに最新を出せる形にしてある。
 */
class Command internal constructor(
    val id: String,
    val label: String,
    val group: CommandGroup,
    /** 狭い場所（記号キー列）に出す短い名前。**意匠なので表とは別に持つ** ── 無ければ [label] を出す。 */
    private val barLabel: String?,
    private val detailOf: () -> String,
    private val availableOf: () -> Boolean,
    private val action: () -> Unit,
    /** まだ実装が無い。押すと「まだ作っていない」と出るだけ。 */
    val planned: Boolean
) {
    /**
     * バーに出す文字。狭いので「ファイルツリー」ではなく「☰」を出す、のような差を吸収する。
     * **記号キー列（E12）も同じ名前を引く** ── 狭い場所は全部ここを見るので、短い名前は1つで済む。
     */
    val onBar: String get() = barLabel ?: label

    val detail: String get() = detailOf()
    val available: Boolean get() = availableOf()

    fun run() = action()
}

/**
 * コマンドの実処理を持つ側（＝ [dev.kirin.kiwa.MainActivity]）。
 *
 * **動作（[CommandActions]）と問い合わせ（[CommandContext]）を型で分けてある。**
 * `tools/check-commands.sh` が見るのは動作の側だけ ── そこを表以外から呼ぶと
 * 「バーからは効くのにパレットからは効かない」食い違いが生まれる。
 * 問い合わせの側は何度どこから呼んでも同じ答えが返るので、縛る意味が無い。
 *
 * **名前で分けず型で分けたのは検査のため。** 「引数の無いメソッドが動作」のような
 * 見分け方にすると、引数を取る動作を1本足した日に**黙って検査の外へ出る**。
 */
interface CommandHost : CommandActions, CommandContext

/**
 * 状態を変える口。**[Commands] からしか呼ばない。**
 *
 * 内部の都合で同じことをしたい場所（自動保存 / タブの × ボタン）は、
 * ここではなく実体の側（`saveActive` / `closeTab`）を呼ぶ。
 */
interface CommandActions {
    /** 保存する。**無題のタブなら [saveAs] と同じく名前を訊く。** */
    fun save()

    /** 名前を付けて保存する（U2）。既に在る名前なら上書きしてよいかを訊く。 */
    fun saveAs()
    fun newTab()

    /** 実体のあるファイルを作って開く。**空のタブを開く [newTab] とは別物。** */
    fun newFile()
    fun closeActiveTab()
    fun undo()
    fun redo()
    fun toggleDrawer()
    fun toggleWrap()

    /** 今のタブの言語を選ぶ（U3）。**そのタブを開いている間だけ**効き、自動にも戻せる。 */
    fun chooseLanguage()

    /** 今のファイルを別の文字コードで開き直す、または別の文字コードで保存する（2026-10-02）。 */
    fun chooseEncoding()

    /** パレットを空で開く。 */
    fun openPalette()

    /** パレットを行モード（`:`）で開く。**行移動の実装はパレットの側にある。** */
    fun openPaletteForLine()

    /** 検索の帯を出す。**閉じるのは帯の側**（✕ と Esc）── 同じ操作で開閉すると、
     * 「開いているか」を見ずに押した時にどちらへ転ぶか分からない。 */
    fun openSearch()

    /** フォルダ全体の検索を開く（E13）。**帯とは別の画面**で、結果の一覧を持つ。 */
    fun openProjectSearch()

    /**
     * 飛ぶ前の場所へ戻る（E13）。
     *
     * **積むのは「飛ぶ」操作の側**（行ジャンプ / 検索を開く / タブの切り替え /
     * 検索結果から開く）で、ここは取り出して移るだけ。
     */
    fun goBack()

    /** 戻る前の場所へ進む（E13）。 */
    fun goForward()

    fun openSettings()
    fun showImeState()
    fun showTraceMenu()

    /** 行コメントの切り替え（E11）。行コメントを持たない言語では、その旨を伝えるだけ。 */
    fun toggleComment()

    /** 字下げする（E11）。 */
    fun indent()

    /** 字下げを戻す（E11）。戻すものが無ければ何もしない。 */
    fun outdent()

    /** 選択している行を上へ動かす（E11）。先頭行を含んでいれば何もしない。 */
    fun moveLineUp()

    /** 選択している行を下へ動かす（E11）。末尾行を含んでいれば何もしない。 */
    fun moveLineDown()
}

/**
 * 表示のための問い合わせと、まだ作っていないことの知らせ。
 * **どこから呼んでも食い違いは生まれない**ので検査の対象外。
 */
interface CommandContext {
    /** まだ作っていないと伝える。 */
    fun notYet(what: String)

    fun canUndo(): Boolean
    fun canRedo(): Boolean

    /** 戻れる場所があるか（E13）。**無ければバーの「元へ」は押せない。** */
    fun canGoBack(): Boolean

    /** 進める場所があるか（E13）。 */
    fun canGoForward(): Boolean

    /** フォルダ全体の検索がどこを根にするか。パレットの説明に出す。 */
    fun searchRootName(): String

    fun activeFileName(): String?

    /** 今のタブの言語の短い名前。手で選んだものには印が付く（U3）。 */
    fun languageLabel(): String

    /** 今のタブの文字コードの短い名前。無題なら保存したときに使う既定（設定）。 */
    fun charsetLabel(): String
    fun drawerOpen(): Boolean
    fun wordWrap(): Boolean
    fun hasTrace(): Boolean
}
