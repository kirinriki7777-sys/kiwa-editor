package dev.kirin.kiwa.palette

import dev.kirin.kiwa.command.Commands
import dev.kirin.kiwa.command.FakeCommandHost
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **E10b の受入の片側** ── 「実装済みの層①が全部パレットから引ける」を機械で踏む。
 *
 * 鎖はこう繋がっている:
 *
 *   層①（表A） → 表B → 実装（`Commands.kt`）  … `tools/check_commands_table.py`
 *   実装 → パレットの一覧 → 打てば引ける        … このファイル
 *
 * だから**片方だけでは足りない** ── 表と実装が合っていてもパレットが別の並びを作れるし、
 * パレットが表を引いていても表そのものが層①を落としていれば意味が無い。
 */
class PaletteItemsTest {

    private fun commands() = Commands.build(FakeCommandHost())

    // ------------------------------------------------------------------
    // コマンド
    // ------------------------------------------------------------------

    @Test
    fun `表のコマンドがそのままパレットに並ぶ`() {
        val commands = commands()
        val items = PaletteItems.forCommands(commands)
        assertEquals(commands.all.map { it.label }, items.map { it.title })
    }

    @Test
    fun `どのコマンドも id を打てば引ける`() {
        val commands = commands()
        val items = PaletteItems.forCommands(commands)
        for (command in commands.all) {
            val hit = Fuzzy.rank(items, command.id, items.size) { it.searchText }
            assertTrue("${command.id} が id で引けない", hit.any { it.title == command.label })
        }
    }

    @Test
    fun `どのコマンドも名前を打てば引ける`() {
        val commands = commands()
        val items = PaletteItems.forCommands(commands)
        for (command in commands.all) {
            val hit = Fuzzy.rank(items, command.label, items.size) { it.searchText }
            assertTrue("${command.label} が名前で引けない", hit.any { it.title == command.label })
        }
    }

    @Test
    fun `まだ作っていないものも並ぶが そう書いてある`() {
        val commands = commands()
        val items = PaletteItems.forCommands(commands)
        for (command in commands.all.filter { it.planned }) {
            val item = items.first { it.title == command.label }
            assertTrue(
                "planned なのに黙って並んでいる: ${command.id}",
                item.detail.contains("まだ作っていない")
            )
        }
    }

    @Test
    fun `今できないものは出るが押せない`() {
        // 戻す編集が無ければ戻せない。**消すのではなく押せなくする**
        // ── 消すと「戻すはどこ？」になる。
        val items = PaletteItems.forCommands(
            Commands.build(FakeCommandHost(file = null, undoable = false))
        )
        assertFalse(items.first { it.title == "戻す" }.enabled)
        assertTrue(items.first { it.title == "設定" }.enabled)
        // 無題のタブでも保存は押せる（U2）── 押すと名前を訊く。
        assertTrue(items.first { it.title == "保存" }.enabled)
    }

    @Test
    fun `選ぶと表の実処理が動く`() {
        val host = FakeCommandHost()
        val items = PaletteItems.forCommands(Commands.build(host))
        items.first { it.title == "保存" }.run()
        items.first { it.title == "行へ移動" }.run()
        // **パレットが自前の実装を持たない**ことの確認 ── 表の口がそのまま呼ばれる。
        assertEquals(listOf("save", "openPaletteForLine"), host.calls)
    }

    // ------------------------------------------------------------------
    // ファイル
    // ------------------------------------------------------------------

    private val root = File("/sdcard/work")

    @Test
    fun `開いているタブが先に出る`() {
        val items = PaletteItems.forFiles(
            openFiles = listOf(File("/sdcard/work/src/Open.kt")),
            indexed = listOf(File("/sdcard/work/a.txt"), File("/sdcard/work/src/Open.kt")),
            root = root
        ) {}
        assertEquals(listOf("Open.kt", "a.txt"), items.map { it.title })
        assertTrue(items.first().detail.startsWith("開いている"))
    }

    @Test
    fun `同じファイルは2度出さない`() {
        val same = File("/sdcard/work/a.txt")
        val items = PaletteItems.forFiles(listOf(same), listOf(same), root) {}
        assertEquals(1, items.size)
    }

    @Test
    fun `道でも引ける`() {
        val items = PaletteItems.forFiles(
            emptyList(), listOf(File("/sdcard/work/ui/PaletteView.kt")), root
        ) {}
        assertEquals("ui/PaletteView.kt", items.first().searchText)
        // 名前だけでなく道の途中も打てる ── 同じ名前のファイルは道でしか区別できない。
        assertTrue(Fuzzy.score(items.first().searchText, "ui pal") != null)
    }

    @Test
    fun `選ぶと開く口が呼ばれる`() {
        var opened: File? = null
        val target = File("/sdcard/work/a.txt")
        PaletteItems.forFiles(emptyList(), listOf(target), root) { opened = it }.first().run()
        assertEquals(target, opened)
    }

    // ------------------------------------------------------------------
    // 行
    // ------------------------------------------------------------------

    @Test
    fun `行モードの候補は1件`() {
        val item = PaletteItems.forLine(PaletteQuery.parse(":12"), lineCount = 300) {}
        assertEquals("12 行目へ", item!!.title)
        assertEquals("全 300 行", item.detail)
    }

    @Test
    fun `行数を超えたら末尾へ寄せる`() {
        var went = -1
        val item = PaletteItems.forLine(PaletteQuery.parse(":900"), lineCount = 300) { went = it }
        item!!.run()
        assertEquals(300, went)
        assertTrue("寄せたことを書いていない", item.detail.contains("末尾"))
    }

    @Test
    fun `数字になっていなければ候補を出さない`() {
        assertNull(PaletteItems.forLine(PaletteQuery.parse(":"), 300) {})
        assertNull(PaletteItems.forLine(PaletteQuery.parse(":x"), 300) {})
        assertNull(PaletteItems.forLine(PaletteQuery.parse("12"), 300) {})
    }
}
