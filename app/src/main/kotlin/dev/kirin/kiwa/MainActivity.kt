package dev.kirin.kiwa

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.kirin.editoradapter.EditorDocument
import dev.kirin.editoradapter.EditorEngine
import dev.kirin.editoradapter.TextPosition
import dev.kirin.editoradapter.TextRange
import dev.kirin.kiwa.command.CommandHost
import dev.kirin.kiwa.command.Commands
import dev.kirin.kiwa.edit.Edit
import dev.kirin.kiwa.edit.LineComments
import dev.kirin.kiwa.edit.LineEdits
import dev.kirin.kiwa.engine.sora.SoraEngine
import dev.kirin.kiwa.file.Encodings
import dev.kirin.kiwa.file.LanguageChoice
import dev.kirin.kiwa.file.NewFile
import dev.kirin.kiwa.file.SaveAs
import dev.kirin.kiwa.file.SaveCleanup
import dev.kirin.kiwa.file.StorageAccess
import dev.kirin.kiwa.file.TextFile
import dev.kirin.kiwa.nav.PositionHistory
import dev.kirin.kiwa.palette.FileIndex
import dev.kirin.kiwa.palette.PaletteItem
import dev.kirin.kiwa.palette.PaletteItems
import dev.kirin.kiwa.palette.PaletteQuery
import dev.kirin.kiwa.settings.EditorSettings
import dev.kirin.kiwa.settings.SettingsActivity
import dev.kirin.kiwa.settings.SettingsStore
import dev.kirin.kiwa.symbols.SymbolKey
import dev.kirin.kiwa.trace.JsonlTrace
import dev.kirin.kiwa.ui.FileTreeView
import dev.kirin.kiwa.ui.FolderChooser
import dev.kirin.kiwa.ui.MenuBarView
import dev.kirin.kiwa.ui.StatusBarView
import dev.kirin.kiwa.ui.TitledFrame
import dev.kirin.kiwa.ui.Palette
import dev.kirin.kiwa.ui.ImeAwareInput
import dev.kirin.kiwa.ui.PaletteView
import dev.kirin.kiwa.ui.ProjectSearchView
import dev.kirin.kiwa.ui.SearchBarView
import dev.kirin.kiwa.ui.SymbolRowView
import dev.kirin.kiwa.ui.TabStripView
import java.io.File

/**
 * 画面の骨格。メニューバーと配色を備える。
 *
 * ```
 * ファイル 編集 移動 検索 表示 アプリ          [⌘ 何をする？]   ← メニューバー。位置が変わらない
 * ┌ エクスプローラー ─ × ┐┌ エディタ ──────────────────┐
 * │ ▣ 内部ストレージ     ││ Example.kt ● × │ Example.c × │ +  │
 * │ ▣ SD カード          ││  1 │ fun main() {             │
 * │ ▼ workspace          ││  2 │     foo(a)               │
 * └──────────────────────┘└───────────────────────────────┘
 * 行 7, 桁 9 · ● 未保存                  kotlin · UTF-8 · LF   ← 状態表示（強調の色の地）
 * ```
 *
 * エクスプローラーは**横に広いときは本文の左に並べ、狭いときは被せる**（[setDrawerOpen]）。
 * 前の骨格（案C）は常設バー＋被せる引き出しだった。
 *
 * **決めは1つ ── 位置は変わらない。変わるのは面積だけ。**
 * この端末はキーボードが着脱するので、骨格を2つ持つと着脱のたびに画面が組み変わって
 * 「どこを押すんだっけ」が生まれる。だから引き出しは押し退けず**被せる**し、
 * ソフトキーボードが出ても本文が縮むだけでバーは動かない。
 *
 * **設定はこの画面が持たない。** 値の型は [EditorSettings] ひとつ、
 * エンジンへの適用は `settings.applyTo(...)` ひとつ。
 */
class MainActivity : Activity(), CommandHost {

    /**
     * タブ1枚。**文書の中身はエンジンが持ち、ここが持つのは「どのファイルか」だけ。**
     * Undo 履歴とカーソルは [EditorDocument] の側にあるので、切り替えても消えない。
     */
    private class OpenTab(
        /**
         * 位置履歴が指す先。**並び順（index）では指せない** ── タブを1枚閉じると
         * 以降の index が全部ずれて、履歴が別のタブを指す。ファイルの道でも指せない
         * （まだ保存していないタブには道が無い）。
         */
        val id: Long,
        val document: EditorDocument,
    ) {
        var file: TextFile.Document? = null

        /** **ファイル名から引いた**言語。無題なら null。実際に使うのは [effectiveLanguage]。 */
        var language: String? = null

        /**
         * 言語を手で選んだか。**このタブの中だけ**に持ち、設定にもファイルにも書かない。
         * タブを閉じれば消える。
         */
        var languageChoice: LanguageChoice = LanguageChoice.Auto

        /** 色とインデントに効く言語。手で選んでいればそれ、無ければ名前から引いたもの。 */
        val effectiveLanguage: String? get() = languageChoice.resolve(language)
        var lineSeparator: String? = null
        var savedText: String = ""

        /**
         * 未保存か。**出ていないタブは自分で変わりようがない**ので、
         * 切り替えるときに確定させて、以後はその値を使う。
         */
        var dirty: Boolean = false
        val title: String get() = file?.file?.name ?: "（無題）"
    }

    private lateinit var engine: EditorEngine
    private lateinit var root: LinearLayout
    private lateinit var menuBar: MenuBarView
    private lateinit var tabStrip: TabStripView
    private lateinit var searchBar: SearchBarView
    private lateinit var body: FrameLayout
    private lateinit var drawer: FileTreeView

    /** エクスプローラーの枠（見た目案 01）。**中身は [drawer]。** */
    private lateinit var explorerFrame: TitledFrame

    /** エディタの枠。タブ列・検索の帯・本文を囲む。 */
    private lateinit var editorFrame: TitledFrame
    private lateinit var status: StatusBarView

    /**
     * 記号キー列（E12）。**ソフトキーボードが見えている間だけ出す。**
     * 物理キーボードがあるなら Tab も Esc も矢印も本物がある。
     */
    private lateinit var symbolRow: SymbolRowView

    /**
     * コマンドパレット（E10b）。**配色の [Palette] とは別物**なので名前を分けてある。
     * 引き出しと同じ層に被さり、押す場所（バーの欄）は動かない。
     */
    private lateinit var commandPalette: PaletteView

    /**
     * フォルダ全体の検索（E13）。**パレットと同じ位置・同じ形の別画面**で、
     * 帯（[searchBar]）とは別物 ── あちらは「今の1件」、こちらは一覧を持つ。
     */
    private lateinit var projectSearch: ProjectSearchView

    /**
     * 位置履歴（E13）。**積むのは「飛ぶ」操作の側**（行ジャンプ / 検索を開く /
     * タブの切り替え / 結果から開く）で、[goBack] / [goForward] は取り出して移るだけ。
     */
    private val history = PositionHistory()

    /** タブに配る通し番号。**使い回さない** ── 閉じたタブの番号が再び出ると履歴が別物を指す。 */
    private var nextTabId = 0L

    /**
     * 検索を最後に引き直したときの本文。**カーソルだけ動いたのを見分ける**ためだけに持つ
     * （エンジンの合図は「中身かカーソルが変わった」までしか言わない）。
     */
    private var searchedText: String = ""

    /** パレットのファイルモードが引く索引。**根が変わるまで使い回す。** */
    private var indexRoot: File? = null
    private var indexed: FileIndex.Result = FileIndex.Result.EMPTY

    /** ソフトキーボードが見えているか。記号キー列の出し入れだけに使う。 */
    private var imeVisible = false

    /**
     * 操作の表。**画面に出る口はここからしか生えない**（E10）。
     * バー・オーバーフロー・パレットが同じ表を引くので、同じ操作が2つの実装を持てない。
     */
    private lateinit var commands: Commands

    private lateinit var store: SettingsStore
    private lateinit var settings: EditorSettings
    private lateinit var palette: Palette

    private val tabs = ArrayList<OpenTab>()
    private var activeIndex = -1

    /**
     * 実機ゲート用の計測。**debug ビルドでだけ作る**ので、release では
     * 境界から呼び出しすら起きない（`SoraEngine.create` の trace が null になる）。
     */
    private var trace: JsonlTrace? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trace = if (BuildConfig.DEBUG) JsonlTrace(this) else null
        engine = SoraEngine.create(this, trace)

        store = SettingsStore(this)
        settings = store.load()
        palette = settings.theme.resolve(this)

        // 画面より先に組む ── バーもオーバーフローもここから引く。
        commands = Commands.build(this)

        buildScreen()
        setContentView(root)
        applySystemInsets(root)

        // 打つたびに状態表示を描き直す。**押した時だけ描き直す形では
        // 「1文字打っても未保存が出ない」になる**（2026-09-06 の実機で見つけた）。
        engine.setOnChangeListener { onEditorChanged() }
        // 検索は別スレッドで数えるので、**届いたときに件数を聞き直す**（E11）。
        engine.search().setOnResultListener { searchBar.onSearchResult() }
        // Home は Sora より先に取る（E）。**エンジンの口は1つも増えていない**。
        engine.asView().setOnKeyListener { _, keyCode, event -> onEditorKey(keyCode, event) }
        // 記号キー列の投げ先は本文で固定なので、本文がフォーカスを失ったら効かせない（E12）。
        engine.asView().setOnFocusChangeListener { _, hasFocus -> symbolRow.setFocused(hasFocus) }
        // **本文を触ったら Enter は本文のもの**（検索の帯は開いたままでよい）。
        // 消費はしない ── Sora の選択・カーソル操作をそのまま通す。
        engine.asView().setOnTouchListener { _, _ ->
            searchBar.noteBodyTouched()
            false
        }

        openBlankTab()
        applyAll()
    }

    // ------------------------------------------------------------------
    // 画面を組む
    // ------------------------------------------------------------------

    private fun buildScreen() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // **メニューは表から組む**（案A）。表に足したコマンドは必ずどこかのメニューに出る。
        menuBar = MenuBarView(this, commands = { commands }, onPalette = { commands["view.palette"].run() })
        menuBar.rebuild()
        root.addView(
            menuBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // ---- エディタの枠: タブ列 / 検索の帯 / 本文 ----
        val editorColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // **タブを押すのも「飛ぶ」。** 別のファイルへ移った後、元居た場所へ1手で戻れる。
        tabStrip = TabStripView(
            this,
            onSelect = { if (it != activeIndex) jump { showTab(it) } },
            onClose = { closeTab(it) },
            // **`+` も表を通す。** ここで直に開くと、表の外に道が1本できる。
            onNew = { commands["file.newTab"].run() }
        )
        editorColumn.addView(
            tabStrip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // **タブ列と本文の間に挟む。** 被せると本文が読めなくなる ── 検索は本文を見ながら操作する。
        // 縮むのは本文だけで、メニューバーもタブ列も動かない（「位置は変わらない」）。
        searchBar = SearchBarView(
            this,
            engine.search(),
            trace,
            onClosed = { engine.asView().requestFocus() }
        )
        editorColumn.addView(
            searchBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        editorColumn.addView(
            engine.asView(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        editorFrame = TitledFrame(this).apply {
            setTitle("エディタ")
            setContent(editorColumn)
        }

        // ---- エクスプローラーの枠 ----
        // 被せているときだけ、選んだら閉じる（並べているときは開いたまま ── 次のファイルもそこから選ぶ）。
        drawer = FileTreeView(this) { file ->
            jump { openFile(file) }
            if (!explorerDocked()) setDrawerOpen(false)
        }
        explorerFrame = TitledFrame(this).apply {
            setTitle("エクスプローラー")
            setContent(drawer)
            // **× も表を通す**（☰ と同じ `view.drawer`）。
            setOnClose { commands["view.drawer"].run() }
            visibility = View.GONE
        }

        // 本文とエクスプローラーは同じ層に置き、並べるときは本文の左端をずらす（[layoutPanes]）。
        body = FrameLayout(this).apply { setPadding(PAD / 2, PAD / 2, PAD / 2, PAD / 4) }
        body.addView(
            editorFrame,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        body.addView(
            explorerFrame,
            FrameLayout.LayoutParams(explorerWidth(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        )
        // **パレットはエクスプローラーより上に被せる。** 開いたままでも呼べる。
        commandPalette = PaletteView(
            this,
            trace,
            source = { query -> paletteItems(query) },
            onClosed = { engine.asView().requestFocus() }
        )
        body.addView(
            commandPalette,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // **パレットと同じ層・同じ位置**に置く。
        projectSearch = ProjectSearchView(
            this,
            trace,
            rootOf = { treeRoot() },
            onChoose = { file, line ->
                // **飛ぶ前に今の場所を積む。** 一覧は閉じているので、戻り道はここでしか作れない。
                jump {
                    openFile(file)
                    goToLine(line)
                }
            },
            onClosed = { engine.asView().requestFocus() }
        )
        body.addView(
            projectSearch,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        status = StatusBarView(this).apply {
            // 右端の文字コードの札（2026-10-02）。**入口も表を通す**（メニューバーと同じ）。
            // ここで直に開くと、表の外に道が1本できる。
            onCharsetClick = { commands["file.encoding"].run() }
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // **一番下（状態表示より下）。** ソフトキーボードのすぐ上に来る位置で、
        // 出入りしても本文より上のものは1つも動かない（「位置は変わらない」）。
        symbolRow = SymbolRowView(
            this,
            onRawKey = { key, ctrl, shift -> sendRawKey(key, ctrl, shift) },
            onText = { key -> insertSymbol(key.text ?: "") },
            onCommand = { key -> key.commandId?.let { commands.find(it)?.run() } },
            // **記録は押すたびに1行。** 動いた押しと動かなかった押しを同じ形で残す。
            onPress = { key, handled, note ->
                trace?.event(
                    "app", "symbol.press",
                    "id", key.id, "kind", key.kind.name, "handled", handled, "note", note
                )
            }
        )
        symbolRow.visibility = View.GONE
        root.addView(
            symbolRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    /** エクスプローラーの幅（px）。 */
    private fun explorerWidth(): Int = (EXPLORER_WIDTH_DP * resources.displayMetrics.density).toInt()

    /**
     * エクスプローラーを本文の左に**並べる**か。横に広いときだけ並べ、狭いときは被せる ──
     * 縦向きで並べると本文が半分になる（案C で「常設にしない」とした理由はそのまま生きている）。
     */
    private fun explorerDocked(): Boolean =
        resources.configuration.screenWidthDp >= DOCK_MIN_WIDTH_DP

    /** 並べているなら本文の左端をエクスプローラーの幅だけずらす。被せているなら重ねる。 */
    private fun layoutPanes() {
        val open = explorerFrame.visibility == View.VISIBLE
        val params = editorFrame.layoutParams as FrameLayout.LayoutParams
        val start = if (open && explorerDocked()) explorerWidth() + PAD / 2 else 0
        if (params.marginStart != start) {
            params.marginStart = start
            editorFrame.layoutParams = params
        }
        // 今いる領域の枠を強調する（01）。被せて開いているときはエクスプローラー、それ以外は本文。
        val explorerOnTop = open && !explorerDocked()
        editorFrame.apply(palette, emphasized = !explorerOnTop)
        explorerFrame.apply(palette, emphasized = explorerOnTop)
    }

    // ------------------------------------------------------------------
    // コマンドの実処理（CommandHost）
    //
    // **ここは Commands.kt からしか呼ばれない。** `tools/check-commands.sh` が機械で見ている ──
    // 画面のどこかが表を通さずに直に呼ぶと、「バーからは効くのにパレットからは効かない」が生まれる。
    // 内部の都合で同じことをしたい場所（自動保存・タブの×）は、
    // 実体の側（`saveActive` / `closeTab`）を呼ぶので混ざらない。
    // ------------------------------------------------------------------

    /** **無題のタブは名前を訊く**（U2）。保存先が無いと言って終わると、書いたものを残す道が無い。 */
    override fun save() {
        if (activeTab()?.file == null) showSaveAs() else saveActive()
    }

    override fun saveAs() = showSaveAs()

    override fun newTab() = openBlankTab()

    /**
     * 新しいファイルを作る（`file.new`）。**空のタブ（`file.newTab`）とは別物**で、
     * こちらは実体を作るので保存先がその場で決まる。
     *
     * 名前の欄は相対の道も絶対の道も受けるので（[NewFile.resolve]）、`docs/note.md` と打てば潜らずに済む。
     * **潜って選ぶ道も「場所を選ぶ…」で持つ**。根の外へ作るのに道を全部覚えて打つのは、
     * 保存先と同じ困り方になる。
     *
     * **打っている最中に行き先を出す。** 押すまで結果が分からないと、
     * 打ち間違えた道に黙って作られたのか断られたのかが後から見えない。
     */
    override fun newFile() {
        if (!StorageAccess.hasAccess()) {
            StorageAccess.promptFor(this)
            return
        }
        var root = treeRoot()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD * 3, PAD * 2, PAD * 3, PAD)
        }
        // **名前も IME を通る。** ファイル名は日本語もありうるので FORCE_ASCII で殴らず、
        // 代わりに**行き先をそのまま出して**打った通りのものが見えるようにする。
        val name = ImeAwareInput(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "main.go"
            textSize = 16f
        }
        val preview = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.dim)
            setPadding(0, PAD, 0, 0)
        }
        fun renderPreview() {
            preview.text = when (val resolved = NewFile.resolve(root, name.rawText())) {
                is NewFile.Result.Ready -> "→ ${resolved.file.absolutePath}"
                is NewFile.Result.Rejected -> resolved.reason
            }
        }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderPreview()
        })
        renderPreview()
        layout.addView(placeRow(root) { chosen ->
            root = chosen
            renderPreview()
        })
        layout.addView(name)
        layout.addView(preview)

        AlertDialog.Builder(this)
            .setTitle("新しいファイル")
            .setView(layout)
            // **押した時にもう一度判定する** ── 打ってから押すまでの間に、
            // 母艦から同じ名前が届いていることがある（Syncthing）。
            .setPositiveButton("作る") { _, _ -> createFile(root, name.rawText()) }
            .setNegativeButton("やめる", null)
            .show()
    }

    /**
     * 「場所: …」と「場所を選ぶ…」の1行。**新しいファイルと名前を付けて保存が同じ行を使う**
     * （場所の決め方が2通りあると、片方だけ選べない形がまた生まれる）。
     *
     * @param onChosen 選び直すたびに呼ぶ。相対で打った名前の起点を差し替えるのは呼び出し側
     */
    private fun placeRow(start: File, onChosen: (File) -> Unit): View {
        var current = start
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val where = TextView(this).apply {
            text = "場所: ${start.absolutePath}"
            textSize = 12f
            setTextColor(palette.dim)
            // 長い道は頭を削る ── 見分けたいのは末尾の方。
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        val choose = Button(this).apply {
            text = "場所を選ぶ…"
            isAllCaps = false
            setOnClickListener {
                FolderChooser.show(this@MainActivity, current, settings.showHiddenFiles) { chosen ->
                    current = chosen
                    where.text = "場所: ${chosen.absolutePath}"
                    onChosen(chosen)
                }
            }
        }
        row.addView(where, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(choose)
        return row
    }

    private fun createFile(root: File, input: String) {
        when (val resolved = NewFile.resolve(root, input)) {
            is NewFile.Result.Rejected -> toast(resolved.reason)
            is NewFile.Result.Ready -> {
                val failure = NewFile.create(resolved.file)
                if (failure != null) {
                    toast(failure)
                    return
                }
                // **開く道は1本。** 引き出しから選んだときと同じ口を通す。
                openFile(resolved.file)
            }
        }
    }

    override fun closeActiveTab() = closeTab(activeIndex)

    override fun undo() {
        if (engine.canUndo()) engine.undo()
        onEditorChanged()
    }

    override fun redo() {
        if (engine.canRedo()) engine.redo()
        onEditorChanged()
    }

    override fun toggleDrawer() = setDrawerOpen(explorerFrame.visibility != View.VISIBLE)

    /**
     * 今のタブの言語を選ぶ（U3）。**効くのはこのタブだけ**で、タブを閉じれば消える。
     *
     * 先頭の「自動」でファイル名から引く状態へ戻せる。今の選択には ● を付ける。
     */
    override fun chooseLanguage() {
        val tab = activeTab() ?: return
        val options = LanguageChoice.options(tab.language, tab.languageChoice, settings.extraExtensions.values)
        val labels = options.map { (if (it.selected) "● " else "　 ") + it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("言語を選ぶ（このタブだけ）")
            .setItems(labels) { _, which ->
                // 訊いている間にタブが替わっていたら、選んだ相手は出ているタブではない。
                if (activeTab() !== tab) {
                    toast("タブが替わったので変えていない ── もう一度選んで")
                    return@setItems
                }
                tab.languageChoice = options[which].choice
                // 言語が変わるとインデントも変わる（言語別の上書き）── 適用の口を1本だけ通す。
                settings.applyTo(engine, palette.editorTheme, currentDocument())
                refreshStatus()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /**
     * 文字コードを変える（2026-10-02）。**開き直す**か**別の文字コードで保存する**かを先に訊く ──
     * 同じ「Shift_JIS を選ぶ」でも、片方はバイトをそのままに読み方を変え、
     * もう片方は読み方をそのままにバイトを変える。1つの一覧に混ぜると取り違える。
     */
    override fun chooseEncoding() {
        val tab = activeTab() ?: return
        val document = tab.file ?: return
        AlertDialog.Builder(this)
            .setTitle("文字コード（今: ${Encodings.label(document)}）")
            .setItems(arrayOf("別の文字コードで開き直す", "別の文字コードで保存する")) { _, which ->
                when (which) {
                    0 -> chooseReopenEncoding(tab, document)
                    1 -> chooseSaveEncoding(tab, document)
                }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun chooseReopenEncoding(tab: OpenTab, document: TextFile.Document) {
        val choices = Encodings.REOPEN
        val labels = choices.map { choice ->
            val selected = choice.charset.name() == document.charset.name()
            (if (selected) "● " else "　 ") + choice.charsetName
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("開き直す文字コード")
            .setItems(labels) { _, which ->
                val choice = choices[which]
                if (!tab.dirty) {
                    reopenWith(tab, document, choice)
                    return@setItems
                }
                // **未保存の変更を黙って捨てない。** 開き直しはファイルのバイトから読み直す。
                AlertDialog.Builder(this)
                    .setTitle("未保存の変更がある")
                    .setMessage("開き直すと ${tab.title} の未保存の変更は消える。")
                    .setPositiveButton("捨てて開き直す") { _, _ -> reopenWith(tab, document, choice) }
                    .setNegativeButton("やめる", null)
                    .show()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun reopenWith(tab: OpenTab, document: TextFile.Document, choice: Encodings.Choice) {
        // 訊いている間にタブが替わっていたら、出ている本文は [tab] のものではない。
        if (activeTab() !== tab) {
            toast("タブが替わったので開き直していない ── もう一度選んで")
            return
        }
        val file = document.file
        val raw = try {
            if (file.length() > settings.maxOpenBytes()) {
                toast("大きすぎて開けない（${file.length() / 1024 / 1024}MB / 上限 ${settings.maxOpenMegabytes}MB）")
                return
            }
            file.readBytes()
        } catch (e: Exception) {
            toast("開けなかった: ${e.message}")
            return
        }
        when (val reopened = Encodings.reopen(file, raw, choice)) {
            is Encodings.Reopened.Rejected -> toast(reopened.reason)
            is Encodings.Reopened.Ready -> {
                val next = reopened.document
                tab.file = next
                // 先に「保存済みの本文」を差し替える ── 流し込んだ合図で未保存の印が一瞬付かないように。
                tab.savedText = next.text
                tab.dirty = false
                tab.lineSeparator = TextFile.dominantLineSeparator(next.text, settings.defaultLineSeparator)
                engine.setText(next.text)
                // 改行の流儀が変わりうるので、適用の口を1本だけ通す（`writeAs` と同じ）。
                settings.applyTo(engine, palette.editorTheme, currentDocument())
                // **本文が別物になったので検索を引き直す**（[showTab] と同じ）。エンジンは `setText` で検索を捨てるので、
                // ここで引き直さないと検索欄に語が残ったまま、次へ・置換が効かなくなる。
                searchedText = engine.getText().toString()
                searchBar.reapply()
                toast("${choice.charsetName} で開き直した")
                refreshTabs()
                refreshStatus()
            }
        }
    }

    private fun chooseSaveEncoding(tab: OpenTab, document: TextFile.Document) {
        val choices = Encodings.Choice.entries
        val current = Encodings.current(document)
        val labels = choices.map { (if (it == current) "● " else "　 ") + it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("保存する文字コード")
            .setItems(labels) { _, which -> saveWithEncoding(tab, document, choices[which]) }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun saveWithEncoding(tab: OpenTab, document: TextFile.Document, choice: Encodings.Choice) {
        if (activeTab() !== tab) {
            toast("タブが替わったので保存していない ── もう一度選んで")
            return
        }
        cleanUpBeforeSave(tab)
        val text = engine.getText().toString()
        val next = when (val prepared = Encodings.convert(document, text, choice)) {
            is SaveAs.Prepared.Rejected -> {
                toast(prepared.reason)
                return
            }
            is SaveAs.Prepared.Ready -> prepared.document
        }
        try {
            TextFile.write(next, text)
        } catch (e: Exception) {
            toast("保存できなかった: ${e.message}")
            return
        }
        tab.file = next
        tab.savedText = text
        tab.dirty = false
        toast("保存した: ${next.file.name}（${choice.label}）")
        refreshTabs()
        refreshStatus()
    }

    /**
     * 保存の後始末（設定の「行末の空白を消す」「最後に改行を足す」）。**本文そのものを直す**
     * （書くバイトだけ直すと、保存した直後から画面とファイルが食い違う）。
     *
     * **変換中は触らない** ── 未確定の文字の周りを書き換えると IME の持っている範囲とずれる
     * （このアプリが一番守っている境界）。その回は後始末をせずに、そのまま保存する。
     */
    private fun cleanUpBeforeSave(tab: OpenTab) {
        if (!settings.trimTrailingWhitespace && !settings.insertFinalNewline) return
        if (engine.ime().isComposing) return
        val text = engine.getText().toString()
        val cleaned = SaveCleanup.clean(
            text,
            settings.trimTrailingWhitespace,
            settings.insertFinalNewline,
            tab.lineSeparator ?: settings.defaultLineSeparator
        )
        val edit = SaveCleanup.edit(text, cleaned) ?: return
        // カーソルと選択は**行と桁で**戻す ── 行末の空白が消えても同じ行に居られるように（桁は行末へ寄る）。
        val before = engine.getSelection()
        engine.replaceRange(
            TextRange(engine.positionOf(edit.start), engine.positionOf(edit.end)),
            edit.replacement
        )
        fun moved(position: TextPosition) =
            engine.positionOf(SaveCleanup.indexOf(cleaned, position.line(), position.column()))
        engine.setSelection(TextRange(moved(before.start()), moved(before.end())))
    }

    override fun openPalette() = showPalette("")

    /** 行モードで開く。**行移動の実装はパレットの側にある**（実装を2つ持たない）。 */
    override fun openPaletteForLine() = showPalette(PaletteQuery.PREFIX_LINE.toString())

    override fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /**
     * 検索の帯を出す。**選んでいた文字をそのまま検索語に入れる。**
     *
     * 改行をまたぐ選択は入れない ── 欄は1行なので、入れても打ち直すことになる。
     */
    override fun openSearch() {
        // **一致から一致への移動では積まない。** 開いた瞬間の場所だけを起点として覚える。
        recordOrigin()
        searchBar.open(selectedWord())
    }

    /** フォルダ全体を検索する（E13）。**選んでいた文字は帯と同じ規則で持っていく。** */
    override fun openProjectSearch() {
        projectSearch.open(selectedWord())
    }

    override fun goBack() = moveThroughHistory { current -> history.goBack(current) }

    override fun goForward() = moveThroughHistory { current -> history.goForward(current) }

    /**
     * 履歴を1つ辿る。**今の場所を渡して、行き先を受け取る** ──
     * 反対側へ積むのは [PositionHistory] の仕事なので、ここには置かない。
     */
    private fun moveThroughHistory(step: (PositionHistory.Spot) -> PositionHistory.Spot?) {
        val current = currentSpot() ?: return
        val spot = step(current) ?: return
        val index = tabs.indexOfFirst { it.id == spot.tabId }
        // 閉じたタブは [PositionHistory.forgetTab] で消えているので、ここへは来ない。
        if (index < 0) return
        if (index != activeIndex) showTab(index)
        goToLine(spot.line)
        trace?.event("app", "history.move", "tab", spot.tabId.toString(), "line", spot.line.toString())
    }

    /** 今どこに居るか。タブが無ければ null（起動直後の一瞬だけ）。 */
    private fun currentSpot(): PositionHistory.Spot? {
        val tab = activeTab() ?: return null
        return PositionHistory.Spot(tab.id, engine.getSelection().start().line() + 1)
    }

    /**
     * 場所を移す操作は全部ここを通す。**移動しなかったら履歴に足さない。**
     *
     * 判定そのものは [PositionHistory.record] が持つ（規則は単体テストが押さえ、
     * ここが持つのは「飛ぶ前と後の場所を渡す」配線だけ）。
     *
     * 積むのは移動の**後**だが、`record` は今の場所を back へ入れて forward を捨てるだけで、
     * 戻る/進む自体はここを通らないので、前に積むのと結果は変わらない。
     */
    private fun jump(move: () -> Unit) {
        val before = currentSpot()
        move()
        val after = currentSpot()
        if (before == null || after == null) return
        history.record(before, after)
    }

    /**
     * 飛び先が**後で決まる**操作の起点を覚える（検索の帯）。
     *
     * 帯を開いた場所へ一度で戻れるのが要る形で、「次へ」を10回押した分だけ積むと
     * 戻るのに10回押すことになる ── だから移動のたびではなく、開いた瞬間に1つだけ積む。
     */
    private fun recordOrigin() {
        val spot = currentSpot() ?: return
        history.record(spot)
    }

    /** 本文で選んでいる語。改行をまたぐものと長すぎるものは持っていかない。 */
    private fun selectedWord(): String? {
        val selection = engine.getSelection()
        if (selection.isEmpty() || selection.length() > MAX_SEED_LENGTH) return null
        return engine.getText()
            .subSequence(selection.start().index(), selection.end().index())
            .toString()
            .takeIf { !it.contains('\n') && !it.contains('\r') }
    }

    override fun notYet(what: String) {
        toast("$what はまだ作っていない")
    }

    /**
     * コメントの切り替え（E11）。**null は「行コメントを持たない言語」の合図**
     * （[LineEdits.toggleComment] の約束）── `notYet` ではなく、そう伝える。
     */
    override fun toggleComment() {
        val lineComment = LineComments.of(activeTab()?.effectiveLanguage)
        if (lineComment == null) {
            toast("この言語には行コメントが無い")
            return
        }
        applyLineEdit { text, start, end -> LineEdits.toggleComment(text, start, end, lineComment) }
    }

    override fun indent() {
        applyLineEdit { text, start, end -> LineEdits.indent(text, start, end, indentUnit()) }
    }

    override fun outdent() {
        applyLineEdit { text, start, end -> LineEdits.outdent(text, start, end, indentUnit()) }
    }

    override fun moveLineUp() {
        applyLineEdit { text, start, end -> LineEdits.moveLineUp(text, start, end) }
    }

    override fun moveLineDown() {
        applyLineEdit { text, start, end -> LineEdits.moveLineDown(text, start, end) }
    }

    /** 今開いている言語に効く1段ぶんの文字。設定の幅とタブ/スペースをそのまま渡す。 */
    private fun indentUnit(): String {
        val (width, useTab) = settings.indentFor(activeTab()?.effectiveLanguage)
        return if (useTab) "\t" else " ".repeat(width)
    }

    /**
     * [LineEdits] の計算結果をエンジンへ適用する。**エンジンへの適用は既にある口2つだけ**
     * （`replaceRange` / `setSelection`）── 新しい口はここで足さない。null（何もしない）が
     * 返ってきたら触らずに帰る。
     */
    private fun applyLineEdit(compute: (String, Int, Int) -> Edit?) {
        val selection = engine.getSelection()
        val edit = compute(engine.getText().toString(), selection.start().index(), selection.end().index())
            ?: return
        engine.replaceRange(TextRange(engine.positionOf(edit.start), engine.positionOf(edit.end)), edit.replacement)
        engine.setSelection(
            TextRange(engine.positionOf(edit.selectionStart), engine.positionOf(edit.selectionEnd))
        )
    }

    /**
     * 本文のキーを Sora より先に見る1枚。**smart home のために、エンジンの口を1つも足さずに済ませる位置**。
     *
     * `View.dispatchKeyEvent` は `OnKeyListener` を `onKeyDown` より先に呼ぶので、
     * Sora の `EditorKeyEventHandler` が `KEYCODE_MOVE_HOME` を
     * `SelectionMovement.ROW_START` へ渡すより前に取れる。
     *
     * **取るのは修飾キーの付かない Home だけ。** 残りは Sora のまま:
     *
     * - **Ctrl+Home** はファイルの先頭へ飛ぶ別の操作
     * - **Shift+Home** は選択の起点（anchor）が要るが、[EditorEngine] は左右しか返さないので
     *   どちらが動く側か分からない。**起点を返す口を足すのは境界を広げること**なので、
     *   Sora の「行頭まで伸ばす」の動きをそのまま残す（賢くはないが壊れてはいない）
     * - **変換中**は触らない。カーソルを未確定文字列の外へ動かすのは、責務3が
     *   キーバインドを黙らせているのと同じ理由で危ない
     *
     * 返り値 `true` はここで食べたという意味で、Sora の Home 処理は動かない。
     */
    private fun onEditorKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_MOVE_HOME) return false

        // **取らなかったことも1行残す。** 出さないと「Sora へ渡した」と「そもそも届いていない」が
        // 同じ無記録になる（E11 で踏んだ形）。
        val reason = when {
            event.isCtrlPressed -> "ctrl"
            event.isAltPressed -> "alt"
            event.isShiftPressed -> "shift"
            engine.ime().isComposing -> "composing"
            else -> null
        }
        if (reason != null) {
            trace?.event("app", "key.home", "handled", false, "reason", reason)
            return false
        }

        val from = engine.getSelection().start().index()
        val to = LineEdits.smartHome(engine.getText().toString(), from)
        engine.setSelection(TextRange(engine.positionOf(to), engine.positionOf(to)))
        trace?.event("app", "key.home", "handled", true, "from", from, "to", to)
        return true
    }

    // ------------------------------------------------------------------
    // 記号キー列（E12）
    // ------------------------------------------------------------------

    /**
     * 生キーを本文へ投げる。**物理キーを押したのと同じ経路**（`dispatchKeyEvent`）に乗せる ──
     * こちらで別の処理を書くと、同じ Tab が「物理キーのときの動き」と
     * 「列から押したときの動き」の2つを持つ。
     *
     * **Shift はキーイベントとして投げないと立たない。** Sora は `KeyMetaStates`
     * （Android の `MetaKeyKeyListener`）で修飾を持っていて、`isShiftPressed()` が見るのは
     * **押下から作った状態**であってイベントの `metaState` ではない
     * （`text/method/KeyMetaStates.java:68`）。だから Shift の DOWN / UP で挟む。
     * **Ctrl は逆に `metaState` の方**を見る（同 :51 `isCtrlPressed = event.isCtrlPressed()`）ので、
     * 目的のキー自身に載せる。**両方載せる**のは、こちらの `onEditorKey` も
     * `event.isShiftPressed` で判断しているため（片方だけだと Shift+Home が smart home に化ける）。
     */
    private fun sendRawKey(key: SymbolKey, ctrl: Boolean, shift: Boolean) {
        val view = engine.asView()
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        if (shift) view.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        view.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, key.keyCode, meta))
        view.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_UP, key.keyCode, meta))
        if (shift) view.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))

    }

    private fun keyEvent(action: Int, keyCode: Int, meta: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, keyCode, 0, meta)
    }

    /**
     * 記号を入れる。**物理キーで打ったのと同じ口**（`typeText`）を通す。
     *
     * `replaceRange` で直に書くと、`(` が自動で閉じず、物理キーで入力した場合と本文が異なる。
     * 入力経路を揃え、括弧を閉じる・選択を囲むかどうかは言語の決まりに従う。
     */
    private fun insertSymbol(text: String) {
        engine.typeText(text)
    }

    override fun canUndo(): Boolean = engine.canUndo()

    override fun canRedo(): Boolean = engine.canRedo()

    override fun canGoBack(): Boolean = history.canGoBack()

    override fun canGoForward(): Boolean = history.canGoForward()

    override fun searchRootName(): String = treeRoot().name

    override fun activeFileName(): String? = activeTab()?.file?.file?.name

    override fun charsetLabel(): String =
        activeTab()?.file?.let { Encodings.label(it) } ?: settings.defaultCharset

    override fun languageLabel(): String =
        activeTab()?.let { LanguageChoice.label(it.language, it.languageChoice) } ?: LanguageChoice.PLAIN_LABEL

    override fun drawerOpen(): Boolean = explorerFrame.visibility == View.VISIBLE

    override fun wordWrap(): Boolean = settings.wordWrap

    /** 計測は debug ビルドにしか無い。**表に載せるかどうかがこれで決まる。** */
    override fun hasTrace(): Boolean = trace != null

    private fun setDrawerOpen(open: Boolean) {
        if (open) {
            drawer.apply(palette, settings)
            drawer.setRoot(treeRoot())
            explorerFrame.visibility = View.VISIBLE
        } else {
            explorerFrame.visibility = View.GONE
        }
        layoutPanes()
    }

    // ------------------------------------------------------------------
    // 設定の適用 ── 入り口はここ1つ
    // ------------------------------------------------------------------

    /**
     * 設定を画面とエンジンへ入れる。呼ばれるのは**起動時 / 設定が変わった時 / ファイルを開いた時**の3つ
     * （タブの切り替えも「開いた時」と同じ ── 言語が変わるので同じ口を通す）。
     *
     * エンジンへ入れる部分は [EditorSettings.applyTo] が全部持っている。
     * `tools/check-settings.sh` がそれを機械で見ている。
     */
    private fun applyAll() {
        palette = settings.theme.resolve(this)
        palette.applyTo(root)
        palette.applySystemBars(this)
        menuBar.apply(palette)
        body.setBackgroundColor(palette.background)
        status.apply(palette)
        if (explorerFrame.visibility == View.VISIBLE) drawer.apply(palette, settings)
        layoutPanes()
        commandPalette.apply(palette)
        projectSearch.apply(palette)
        searchBar.apply(palette)
        symbolRow.setKeys(settings.symbolRowKeys, commands)
        symbolRow.apply(palette)
        updateSymbolRow()
        applyFullscreen()

        settings.applyTo(engine, palette.editorTheme, currentDocument())
        refreshTabs()
        refreshStatus()
    }

    /**
     * 適用の口へ渡す文書。**無題のタブも渡す**（U3）── 無題でも言語を手で選べるので、
     * ここで null にすると選んだ言語が色にもインデントにも届かない。
     * 無題で何も選んでいなければ言語も改行も null で、null を渡したときと同じ既定になる。
     */
    private fun currentDocument(): EditorSettings.Document? {
        val tab = activeTab() ?: return null
        return EditorSettings.Document(tab.effectiveLanguage, tab.lineSeparator)
    }

    private fun applyFullscreen() {
        val controller = window.insetsController ?: return
        if (settings.fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        } else {
            controller.show(WindowInsets.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (settings.theme == dev.kirin.kiwa.ui.ThemeChoice.SYSTEM) applyAll()
        // 向きが変わると幅が変わり、並べるか被せるかが変わる。
        layoutPanes()
    }

    /**
     * システムバーのぶんだけ内側へ寄せる。
     *
     * targetSdk 35 以降は端から端まで描くのが既定なので、何もしないと
     * **ツールバーがステータスバーの下へ潜る**。見た目が重なるだけでなく、
     * その帯はステータスバーの窓が持っていくので**タッチが届かない**
     * （同じ端末で実測済み: 0〜30dp は届かず、30〜36dp は届くが通知シェードが奪う）。
     *
     * 下は IME が出ている間そのぶん空ける。**このとき縮むのは本文だけで、
     * 常設バーもタブ列も動かない**（案Cの「位置は変わらない」）。
     */
    private fun applySystemInsets(root: View) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            // **記号キー列が出るのはソフトキーボードが見えている間だけ**（E12）。
            // 物理キーボードだけのときは Tab も Esc も矢印も本物があるので要らない。
            imeVisible = insets.isVisible(WindowInsets.Type.ime())
            updateSymbolRow()
            insets
        }
    }

    /** 記号キー列を出すか決める。**出す条件は3つとも独立**（設定 / ソフトキーボード / 並びが空でない）。 */
    private fun updateSymbolRow() {
        val show = settings.showSymbolRow && imeVisible && settings.symbolRowKeys.isNotEmpty()
        symbolRow.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // タブ
    // ------------------------------------------------------------------

    private fun activeTab(): OpenTab? = tabs.getOrNull(activeIndex)

    private fun openBlankTab() {
        val tab = OpenTab(nextTabId++, engine.newDocument(""))
        tabs.add(tab)
        showTab(tabs.size - 1)
    }

    /**
     * タブを出す。**出す前に、出ていたタブの未保存を確定させる** ──
     * 出ていないタブは自分で変わりようがないので、ここで固めた値がそのまま正しい。
     */
    private fun showTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        freezeActiveDirty()
        activeIndex = index
        engine.showDocument(tab.document)
        // **言語も改行コードもタブごとに違う**ので、切り替えでも適用の口を通す
        // ── ここで `engine.setLineSeparator()` を直に呼ぶと口が2本になる（check-settings.sh が見ている）。
        settings.applyTo(engine, palette.editorTheme, currentDocument())
        // **本文が別物になったので検索を引き直す。** エンジンは切り替えの時点で検索を捨てている
        // （一致の位置は前の本文の中の数字で、そのままだと別の場所を指す）。
        searchedText = engine.getText().toString()
        searchBar.reapply()
        refreshTabs()
        refreshStatus()
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (index == activeIndex) freezeActiveDirty()
        if (tab.dirty) {
            AlertDialog.Builder(this)
                .setTitle("保存していない変更がある")
                .setMessage(tab.title)
                .setPositiveButton("保存して閉じる") { _, _ ->
                    if (index == activeIndex) {
                        // **保存できたときだけ閉じる。** 書けなかったのに閉じると、
                        // 画面にしか無かった本文がそこで消える（U2 の「書込み失敗でも本文を失わない」）。
                        if (tab.file == null) {
                            // 名前を訊く画面は後から答えが来るので、**閉じる相手は index でなくタブで持つ**
                            // （訊いている間に並びが変わりうる）。やめたら閉じない。
                            showSaveAs { tabs.indexOf(tab).takeIf { it >= 0 }?.let { dropTab(it) } }
                        } else if (saveActive()) {
                            dropTab(index)
                        }
                    } else {
                        toast("出ているタブでないと保存できない ── 開いてから閉じて")
                    }
                }
                .setNeutralButton("捨てて閉じる") { _, _ -> dropTab(index) }
                .setNegativeButton("やめる", null)
                .show()
            return
        }
        dropTab(index)
    }

    private fun dropTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tabs.removeAt(index)
        // **最後の1枚を閉じたら空のタブを出す。** 何も無い状態を作ると
        // 「エディタが消えた」に見えるし、次に打つ場所も無くなる。
        if (tabs.isEmpty()) {
            discard(tab)
            activeIndex = -1
            openBlankTab()
            return
        }
        val next = if (index <= activeIndex) (activeIndex - 1).coerceAtLeast(0) else activeIndex
        activeIndex = -1
        showTab(next)
        discard(tab)
    }

    /**
     * タブ1枚を捨てる。**エンジンの文書と位置履歴を同時に落とす**のがこの口の全部。
     *
     * 2つを別々に書いていたら、**空のタブを実ファイルで置き換える経路で履歴だけが残った**
     * ── 「元へ」が押せるのに何も起きない形で出た（2026-09-08 の実機）。
     * 消えたタブを指す履歴は行き先が無く、押しても黙って何も起きない。
     */
    private fun discard(tab: OpenTab) {
        engine.forgetDocument(tab.document)
        // **開き直さずに消す。** 戻る操作で閉じたタブが蘇ると、利用者が驚く。
        history.forgetTab(tab.id)
    }

    private fun freezeActiveDirty() {
        val tab = activeTab() ?: return
        tab.dirty = engine.getText().toString() != tab.savedText
    }

    private fun refreshTabs() {
        tabStrip.render(
            tabs.map { TabStripView.Tab(it.title, it.dirty) },
            activeIndex,
            palette
        )
    }

    // ------------------------------------------------------------------
    // ファイル
    // ------------------------------------------------------------------

    private fun openFile(file: File) {
        if (!StorageAccess.hasAccess()) {
            StorageAccess.promptFor(this)
            return
        }
        // 同じファイルが既に開いていたらそれを出す。**同じものが2枚並ぶと、
        // どちらを保存したのか分からなくなる。**
        val already = tabs.indexOfFirst { it.file?.file?.absolutePath == file.absolutePath }
        if (already >= 0) {
            showTab(already)
            return
        }

        val document = try {
            TextFile.read(file, settings.maxOpenBytes(), settings.defaultCharset)
        } catch (e: TextFile.TooLargeException) {
            toast("大きすぎて開けない（${e.size / 1024 / 1024}MB / 上限 ${settings.maxOpenMegabytes}MB）")
            return
        } catch (e: Exception) {
            toast("開けなかった: ${e.message}")
            return
        }

        val tab = OpenTab(nextTabId++, engine.newDocument(document.text))
        tab.file = document
        tab.savedText = document.text
        // 文法は流し込む前に決める（`setEditorLanguage` は解析をやり直させる）。
        tab.language = settings.languageOf(file.name)
        // 文字コードと同じで、**推定して直すのではなく元に合わせる**。
        tab.lineSeparator = TextFile.dominantLineSeparator(document.text, settings.defaultLineSeparator)

        // **空のまま使われていないタブ1枚だけの状態なら、そこへ入れ替える。**
        // 起動直後に開くたびに「（無題）」が残るのを避ける。
        val blank = tabs.size == 1 && tabs[0].file == null && !tabs[0].dirty &&
            engine.getText().isEmpty()
        if (blank) {
            val old = tabs[0]
            tabs[0] = tab
            activeIndex = -1
            showTab(0)
            discard(old)
        } else {
            tabs.add(tab)
            showTab(tabs.size - 1)
        }
        rememberDirectory(file.parentFile)

        if (settings.wordWrap && settings.exceedsWrapLimit(engine)) {
            toast("${settings.wrapLineLimit}行を超えたので折り返しを切った")
        }
    }

    /**
     * 今のタブを開いているファイルへ書き戻す。**書けたら true。**
     *
     * 無題のタブには保存先が無いので false を返すだけ ── 名前を訊くのは [save] の側
     * （画面を離れるときの自動保存がここを呼ぶので、ここで画面を出すと勝手にダイアログが開く）。
     */
    private fun saveActive(quiet: Boolean = false): Boolean {
        val tab = activeTab() ?: return false
        val document = tab.file ?: return false
        cleanUpBeforeSave(tab)
        val text = engine.getText().toString()
        try {
            TextFile.write(document, text)
        } catch (e: Exception) {
            toast("保存できなかった: ${e.message}")
            return false
        }
        tab.savedText = text
        tab.dirty = false
        if (!quiet) toast("保存した: ${document.file.name}")
        refreshTabs()
        refreshStatus()
        return true
    }

    /**
     * 名前を付けて保存（U2）。場所の打ち方は「新しいファイル」と同じで、
     * **今の根からの相対パスも絶対パスも受ける**（[SaveAs.resolve]）。
     *
     * 欄には今の名前を入れておく ── 別名保存は「少しだけ違う名前」が多いので、打ち直させない。
     *
     * **場所は「場所を選ぶ…」で潜って選べる**。打つしかないと、根の外へ保存するには
     * 道を全部覚えて打つことになる。選んだ場所は相対で打った名前の起点になる。
     *
     * @param then 保存できたときだけ呼ぶ（「保存して閉じる」が閉じるため）。やめた・失敗したときは呼ばない
     */
    private fun showSaveAs(then: (() -> Unit)? = null) {
        if (!StorageAccess.hasAccess()) {
            StorageAccess.promptFor(this)
            return
        }
        val tab = activeTab() ?: return
        val current = tab.file?.file
        var root = treeRoot()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD * 3, PAD * 2, PAD * 3, PAD)
        }
        val name = ImeAwareInput(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "main.go"
            textSize = 16f
            current?.let {
                setText(it.name)
                setSelection(it.name.length)
            }
        }
        val preview = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.dim)
            setPadding(0, PAD, 0, 0)
        }
        fun renderPreview() {
            preview.text = when (val target = SaveAs.resolve(root, name.rawText(), current)) {
                is SaveAs.Target.Ready -> "→ ${target.file.absolutePath}"
                is SaveAs.Target.Overwrite -> "→ ${target.file.absolutePath}（在るので上書きを訊く）"
                is SaveAs.Target.Same -> "→ ${target.file.absolutePath}（今のファイル）"
                is SaveAs.Target.Rejected -> target.reason
            }
        }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderPreview()
        })
        renderPreview()
        layout.addView(placeRow(root) { chosen ->
            root = chosen
            renderPreview()
        })
        layout.addView(name)
        layout.addView(preview)

        AlertDialog.Builder(this)
            .setTitle("名前を付けて保存")
            .setView(layout)
            // **押した時にもう一度判定する** ── 打ってから押すまでの間に、
            // 母艦から同じ名前が届いていることがある（Syncthing）。
            .setPositiveButton("保存") { _, _ ->
                when (val target = SaveAs.resolve(root, name.rawText(), current)) {
                    is SaveAs.Target.Rejected -> toast(target.reason)
                    is SaveAs.Target.Same -> if (saveActive()) then?.invoke()
                    is SaveAs.Target.Ready -> if (writeAs(tab, target.file)) then?.invoke()
                    is SaveAs.Target.Overwrite ->
                        AlertDialog.Builder(this)
                            .setTitle("同じ名前のファイルが在る")
                            .setMessage("${target.file.absolutePath}\n\n上書きすると元の中身は戻せない。")
                            .setPositiveButton("上書きする") { _, _ ->
                                if (writeAs(tab, target.file)) then?.invoke()
                            }
                            .setNegativeButton("やめる", null)
                            .show()
                }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /**
     * [tab] の本文を [target] へ書き、**タブの行き先をそこへ付け替える**。書けたら true。
     *
     * 書けなかったときはタブを一切触らない ── 元のファイルも、未保存の印も、そのまま残る。
     */
    private fun writeAs(tab: OpenTab, target: File): Boolean {
        // **同じファイルを2枚のタブが指す形を作らない**（`openFile` と同じ理由 ── どちらを保存したのか分からなくなる）。
        val other = tabs.any { it !== tab && it.file?.file?.absolutePath == target.absolutePath }
        if (other) {
            toast("そのファイルは別のタブで開いている ── 先にそちらを閉じて")
            return false
        }
        // 訊いている間にタブが替わっていたら、出ている本文は [tab] のものではない。
        if (activeTab() !== tab) {
            toast("タブが替わったので保存していない ── もう一度押して")
            return false
        }
        cleanUpBeforeSave(tab)
        val text = engine.getText().toString()
        val document = when (val prepared = SaveAs.prepare(target, text, tab.file, settings.defaultCharset)) {
            is SaveAs.Prepared.Rejected -> {
                toast(prepared.reason)
                return false
            }
            is SaveAs.Prepared.Ready -> prepared.document
        }
        try {
            TextFile.write(document, text)
        } catch (e: Exception) {
            toast("保存できなかった: ${e.message}")
            return false
        }
        tab.file = document
        tab.savedText = text
        tab.dirty = false
        // 名前が変わったので文法も引き直す（`.txt` を `.kt` で保存したら色が付く）。
        tab.language = settings.languageOf(target.name)
        // 改行コードは**本文に合わせる**。無題のタブは設定の既定で打たれている。
        tab.lineSeparator = TextFile.dominantLineSeparator(text, tab.lineSeparator ?: settings.defaultLineSeparator)
        // 言語と改行は適用の口を1本だけ通す（`showTab` と同じ ── check-settings.sh が見ている）。
        settings.applyTo(engine, palette.editorTheme, currentDocument())
        rememberDirectory(target.parentFile)
        toast("保存した: ${target.name}")
        refreshTabs()
        refreshStatus()
        return true
    }

    /**
     * 画面を離れるときに保存する（設定が入っているときだけ）。
     *
     * **時間で走らせない。** 打っている最中に書き戻すと、母艦が同じファイルを開いている場合に
     * 中途半端な状態が Syncthing で流れる。区切りがはっきりしているのはここ一点だけ。
     */
    override fun onPause() {
        super.onPause()
        freezeActiveDirty()
        val tab = activeTab()
        if (settings.autoSave && tab?.file != null && tab.dirty) saveActive(quiet = true)
    }

    override fun onResume() {
        super.onResume()
        val reloaded = store.load()
        if (reloaded != settings) {
            settings = reloaded
            // 隠しファイルを出す設定が変わると索引の中身が変わる。**根が同じでも作り直す。**
            indexRoot = null
            applyAll()
        }
    }

    // ------------------------------------------------------------------
    // コマンドパレット（E10b）
    //
    // **入口は1つで、頭の1文字がモードを決める**（無印=ファイル / `>`=コマンド / `:`=行）。
    // ここがするのは**母集団を作ることだけ** ── 絞り込みも並べ替えも描画も
    // `PaletteView` の側にあり、コマンドは `Commands` の表がそのまま出る。
    // ------------------------------------------------------------------

    private fun showPalette(prefix: String) {
        ensureFileIndex()
        commandPalette.open(prefix)
    }

    /**
     * モードごとの母集団。**ここでコマンドを作らない** ── 表に無いものがパレットに出たら、
     * `tools/check-commands.sh` が見ている「口が1本」の外に道ができたということ。
     */
    private fun paletteItems(query: PaletteQuery): List<PaletteItem> = when (query.mode) {
        PaletteQuery.Mode.COMMAND -> PaletteItems.forCommands(commands)

        PaletteQuery.Mode.LINE ->
            listOfNotNull(PaletteItems.forLine(query, settings.lineCount(engine)) { jump { goToLine(it) } })

        PaletteQuery.Mode.FILE -> PaletteItems.forFiles(
            openFiles = tabs.mapNotNull { it.file?.file },
            indexed = indexed.files,
            root = treeRoot(),
            open = { file -> jump { openFile(file) } }
        )
    }

    /**
     * ファイルの索引を用意する。**別のスレッドで走らせて、届いたら流し込む。**
     *
     * この端末の `/sdcard` は写真とキャッシュで数万件あるので、開くたびに数えると
     * パレットが出るまで固まる。開いているタブだけは索引を待たずに出るので、
     * 「開いた瞬間に何も無い」にはならない。
     */
    private fun ensureFileIndex() {
        val root = treeRoot()
        if (indexRoot == root) return
        indexRoot = root
        indexed = FileIndex.Result.EMPTY
        commandPalette.note = "数えている"
        val showHidden = settings.showHiddenFiles
        Thread {
            val result = FileIndex.scan(root, showHidden)
            runOnUiThread {
                // 走っている間に根が変わっていたら捨てる（古い一覧を出さない）。
                if (indexRoot != root) return@runOnUiThread
                indexed = result
                commandPalette.note = FileIndex.note(result)
                commandPalette.refreshIfOpen()
            }
        }.start()
    }

    /** 引き出しとパレットが見る木の根。**2箇所で別々に決めると、出るものが食い違う。** */
    private fun treeRoot(): File = activeTab()?.file?.file?.parentFile ?: lastDirectory()

    /** 行の頭へ飛ぶ。**1 始まり**（状態表示と同じ数え方）。 */
    private fun goToLine(line: Int) {
        val at = engine.positionOf(indexOfLineStart(line))
        engine.setSelection(TextRange(at, at))
        engine.asView().requestFocus()
        refreshStatus()
    }

    /**
     * 行の頭の位置を**エンジンに聞きながら**二分探索で出す。
     *
     * 自分で改行を数えると、`lineCount` がエンジンに聞いているのと**物差しが2本になる**
     * ── CR だけのファイルや折り返しの扱いで食い違い、`:300` が 300 行目でない所へ飛ぶ。
     * 探索は文字数の対数回で終わる（20,000 行のファイルでも 20 回ほど）。
     */
    private fun indexOfLineStart(line: Int): Int {
        val target = (line - 1).coerceAtLeast(0)
        var low = 0
        var high = engine.getText().length
        while (low < high) {
            val mid = (low + high) / 2
            if (engine.positionOf(mid).line() < target) low = mid + 1 else high = mid
        }
        return low
    }

    // ------------------------------------------------------------------
    // 表示
    // ------------------------------------------------------------------

    override fun toggleWrap() {
        if (!settings.wordWrap && settings.exceedsWrapLimit(engine)) {
            // 実機実測: word wrap 有効時の先頭への1文字挿入は行数に比例する
            // （1,000行 3.7ms / 5,000行 13.1ms / 20,000行 56.1ms = 1フレームの3.4倍）。
            toast("${settings.lineCount(engine)}行では折り返せない（上限 ${settings.wrapLineLimit}行）")
            return
        }
        settings = settings.copy(wordWrap = !settings.wordWrap)
        store.save(settings)
        applyAll()
    }

    override fun showImeState() {
        val ime = engine.ime()
        toast("composing=${ime.isComposing} range=${ime.composingRange() ?: "-"}")
    }

    /**
     * 中身かカーソルが変わるたびに呼ばれる。**ここで作り直すのは状態表示だけ。**
     * タブ列は未保存の印が変わった時にしか描き直さない ── 打つたびに
     * ビューを作り直すと、指で押している最中に列が組み変わる。
     */
    private fun onEditorChanged() {
        val tab = activeTab() ?: return
        val text = engine.getText().toString()
        val dirty = text != tab.savedText
        if (dirty != tab.dirty) {
            tab.dirty = dirty
            refreshTabs()
        }
        // **一致の位置は1文字打つだけで後ろが全部ずれる**ので、検索が開いていれば引き直す。
        //
        // **ただし中身が変わったときだけ。** この合図はカーソルが動いただけでも飛んでくるので
        // （エンジンは「中身かカーソル」としか言わない）、そのまま引き直すと
        // **「次へ」を押すたびに全文を数え直す**ことになる ── 実機で見つけた（2026-09-07）。
        if (text != searchedText) {
            searchedText = text
            searchBar.onTextEdited()
        }
        refreshStatus()
    }

    /**
     * 状態表示を描き直す。**左 = 今の状態、右 = ファイルの属性**（見た目案 01）。
     */
    private fun refreshStatus() {
        val tab = activeTab()
        val selection = engine.getSelection()
        val state = buildString {
            append("行 ").append(selection.start().line() + 1)
            append(", 桁 ").append(selection.start().column() + 1)
            if (tab?.dirty == true) append(" · ● 未保存")
            if (engine.ime().isComposing) append(" · 変換中")
        }
        val attributes = buildString {
            append(tab?.title ?: "（無題）")
            // 文字コードは右端の札（[StatusBarView.showCharset]）に出す。
            append(" · ").append(lineSeparatorLabel(tab?.lineSeparator))
            // 手で選んだものは「（手動）」付きで出す ── 名前から決まったのか選んだのかが見分けられないと、
            // 拡張子を変えても色が変わらない理由が画面から分からない。
            tab?.takeIf { it.language != null || it.languageChoice is LanguageChoice.Fixed }?.let {
                append(" · ").append(LanguageChoice.label(it.language, it.languageChoice))
            }
            append(" · ").append(settings.lineCount(engine)).append("行")
            if (settings.wordWrap) append(" · 折り返し")
        }
        status.show(state, attributes)
        // **変換中は記号キー列を黙らせる。** 状態表示と同じ合図で動かすので、
        // 「変換中」の文字と列の見た目がずれない。
        symbolRow.setComposing(engine.ime().isComposing)
        refreshCharsetChip()
    }

    /** 右端の文字コードの札。**無題のタブは押せない**（`file.encoding` の available と同じ判断）。 */
    private fun refreshCharsetChip() {
        val command = commands.find("file.encoding") ?: return
        status.showCharset(charsetLabel(), command.available)
    }

    private fun lineSeparatorLabel(separator: String?): String = when (separator) {
        "\r\n" -> "CRLF"
        "\r" -> "CR"
        else -> "LF"
    }

    // ------------------------------------------------------------------
    // 計測（実機ゲート）
    // ------------------------------------------------------------------

    override fun showTraceMenu() {
        val trace = this.trace ?: return
        val items = arrayOf(
            "新しいセッションを開く",
            "テスト項目を選ぶ（今: ${trace.currentTestId()}）",
            "印をつける",
            "ログの場所を出す"
        )
        AlertDialog.Builder(this)
            .setTitle("計測")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> runCatching { trace.startNewSession() }
                        .onSuccess { toast("記録を開始: ${it.name}") }
                        .onFailure { toast("開始できなかった: ${it.message}") }
                    1 -> chooseTestId(trace)
                    2 -> { trace.mark("manual"); toast("印をつけた") }
                    3 -> toast(trace.currentFile?.absolutePath ?: "まだ開始していない")
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun chooseTestId(trace: JsonlTrace) {
        AlertDialog.Builder(this)
            .setTitle("テスト項目")
            .setItems(TEST_IDS) { _, index ->
                trace.setTestId(TEST_IDS[index])
                toast("テスト項目: ${TEST_IDS[index]}")
            }
            .show()
    }

    /**
     * キーを配る一番外側。
     *
     * **検索の帯が開いていて、最後に触られたのが帯なら、Enter と Esc は帯のもの。**
     * フォーカスでは決められない ── エンジンは一致へ飛ぶたび遅れてフォーカスを取るので、
     * 検索欄で Enter を連打すると2回目から**本文に改行が入る**（実機 G。断る/取り返すの
     * 両方を試して、どちらも遅れの幅に負けた）。触られた場所は誰も横取りしないので、
     * そこを所有権の根拠にする。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 被さっている画面から順に訊く。**開いているものが1つだけ**なので順番で悩む余地は無い。
        if (commandPalette.consumeKey(event)) return true
        if (projectSearch.consumeKey(event)) return true
        if (searchBar.consumeKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        engine.setOnChangeListener(null)
        trace?.close()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // 最後に開いたディレクトリ
    // ------------------------------------------------------------------

    private fun lastDirectory(): File {
        if (!settings.rememberLastDirectory) return Environment.getExternalStorageDirectory()
        val saved = prefs().getString(KEY_LAST_DIR, null)?.let { File(it) }
        return if (saved != null && saved.isDirectory) saved else Environment.getExternalStorageDirectory()
    }

    private fun rememberDirectory(dir: File?) {
        if (!settings.rememberLastDirectory) return
        dir ?: return
        prefs().edit().putString(KEY_LAST_DIR, dir.absolutePath).apply()
    }

    private fun prefs() = getSharedPreferences("kiwa", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_LAST_DIR = "last_dir"
        const val PAD = 12

        /** 選んでいた文字を検索語へ写す上限。長い選択は「探したい語」ではない。 */
        const val MAX_SEED_LENGTH = 200

        /** エクスプローラーの幅（dp）。見た目案 01 の 300 に、SD カードの UUID が収まるぶん足した。 */
        const val EXPLORER_WIDTH_DP = 320

        /** この幅（dp）以上ならエクスプローラーを本文の左に並べる。未満なら被せる。 */
        const val DOCK_MIN_WIDTH_DP = 960

        val TEST_IDS = arrayOf(
            "-",
            "E3-1-highlight", "E3-2-undo", "E3-3-tab", "E3-4-hardkeyboard",
            "E4-diff",
            "T03-arrows", "T04-backspace", "T05-enter", "T15-stale",
            "E9-skeleton", "E9-tabs",
            "E10b-palette", "E10b-composing",
            "E11-search", "E11-cross", "E11-newfile",
            "E-home", "E12-symbols"
        )
    }
}
