package dev.kirin.kiwa.command

/**
 * 何が呼ばれたかを数えるだけの受け皿。**表の試験とパレットの試験が同じものを使う** ──
 * [CommandActions] に口が増えたときに直す場所を2箇所へ散らさない。
 */
internal class FakeCommandHost(
    private val trace: Boolean = true,
    private val undoable: Boolean = true,
    private val file: String? = "sample.go",
    private val drawer: Boolean = false,
    private val wrap: Boolean = false,
    private val back: Boolean = true,
    private val forward: Boolean = true
) : CommandHost {
    val calls = ArrayList<String>()
    val notYetFor = ArrayList<String>()

    override fun save() { calls.add("save") }
    override fun saveAs() { calls.add("saveAs") }
    override fun newTab() { calls.add("newTab") }
    override fun newFile() { calls.add("newFile") }
    override fun closeActiveTab() { calls.add("closeActiveTab") }
    override fun undo() { calls.add("undo") }
    override fun redo() { calls.add("redo") }
    override fun toggleDrawer() { calls.add("toggleDrawer") }
    override fun toggleWrap() { calls.add("toggleWrap") }
    override fun chooseLanguage() { calls.add("chooseLanguage") }
    override fun chooseEncoding() { calls.add("chooseEncoding") }
    override fun openPalette() { calls.add("openPalette") }
    override fun openPaletteForLine() { calls.add("openPaletteForLine") }
    override fun openSearch() { calls.add("openSearch") }
    override fun openProjectSearch() { calls.add("openProjectSearch") }
    override fun goBack() { calls.add("goBack") }
    override fun goForward() { calls.add("goForward") }
    override fun openSettings() { calls.add("openSettings") }
    override fun showImeState() { calls.add("showImeState") }
    override fun showTraceMenu() { calls.add("showTraceMenu") }
    override fun toggleComment() { calls.add("toggleComment") }
    override fun indent() { calls.add("indent") }
    override fun outdent() { calls.add("outdent") }
    override fun moveLineUp() { calls.add("moveLineUp") }
    override fun moveLineDown() { calls.add("moveLineDown") }

    override fun notYet(what: String) { notYetFor.add(what) }

    override fun canUndo(): Boolean = undoable
    override fun canRedo(): Boolean = undoable
    override fun canGoBack(): Boolean = back
    override fun canGoForward(): Boolean = forward
    override fun searchRootName(): String = "docs"
    override fun activeFileName(): String? = file
    override fun languageLabel(): String = "go"
    override fun charsetLabel(): String = "UTF-8"
    override fun drawerOpen(): Boolean = drawer
    override fun wordWrap(): Boolean = wrap
    override fun hasTrace(): Boolean = trace
}
