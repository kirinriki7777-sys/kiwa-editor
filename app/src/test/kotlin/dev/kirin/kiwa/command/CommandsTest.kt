package dev.kirin.kiwa.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作の表そのものの試験。**実機も画面も要らない部分**をここで押さえる。
 *
 * 見ているのは4つ:
 *
 *   1. id が重複していない（`get` が黙って片方を隠さない）
 *   2. 常設バーとオーバーフローが**実在の id** を引いている
 *   3. **まだ作っていないコマンドは実処理を呼ばない** ── planned なのに動くと、
 *      表の状態と実装が食い違ったまま実機まで行く
 *   4. **計測は debug ビルドでだけ表に載る** ── release で灰色のまま並べない
 */
class CommandsTest {

    // ------------------------------------------------------------------
    // 表の形
    // ------------------------------------------------------------------

    @Test
    fun `id が重複していない`() {
        val all = Commands.build(FakeCommandHost()).all.map { it.id }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `全部のコマンドがちょうど1つのメニューに出る`() {
        val commands = Commands.build(FakeCommandHost())
        val inMenus = commands.menus().flatMap { (_, items) -> items.map { it.id } }
        assertEquals(commands.all.map { it.id }.sorted(), inMenus.sorted())
    }

    @Test
    fun `メニューは分類の順に並び、空のメニューは出さない`() {
        val menus = Commands.build(FakeCommandHost()).menus()
        val groups = menus.map { it.first }
        val order = groups.map { it.ordinal }
        assertEquals(order.sorted(), order)
        assertTrue(menus.all { it.second.isNotEmpty() })
        // 最初はファイル。案A の「ファイル / 編集 / …」の並び
        assertEquals(CommandGroup.FILE, groups.first())
    }

    @Test
    fun `知らない id を引くと落ちる`() {
        val commands = Commands.build(FakeCommandHost())
        try {
            commands["no.such.command"]
            throw AssertionError("知らない id が通ってしまった")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no.such.command"))
        }
    }

    // ------------------------------------------------------------------
    // まだ作っていないもの
    // ------------------------------------------------------------------

    /**
     * **表に planned が1本も無くなっても、仕組みの側は検査し続ける**（E13 / 2026-09-08）。
     *
     * 元は「表の planned を全部押す」形だったが、層①も層②も作り切って planned が 0 件になり、
     * 「1本も無い」で落ちるようになった。**表に在るかどうかで検査が消える形にしない** ──
     * 次に planned を足した日に、押しても実処理を呼ばないことを誰も見ていない状態になる。
     */
    @Test
    fun `まだ作っていないコマンドは実処理を呼ばない`() {
        val host = FakeCommandHost()
        val command = Command(
            "test.planned", "まだ無いもの", CommandGroup.EDIT, null,
            { "" }, { true }, { host.notYet("まだ無いもの") }, planned = true
        )

        command.run()

        assertTrue("planned が実処理を呼んだ: ${host.calls}", host.calls.isEmpty())
        assertEquals(listOf("まだ無いもの"), host.notYetFor)
    }

    /** 表の側は**今どうなっているか**を見る。作り切ったので planned は 0 件。 */
    @Test
    fun `層①と層②を作り切ったので表に planned は残っていない`() {
        val commands = Commands.build(FakeCommandHost())
        assertEquals(emptyList<String>(), commands.all.filter { it.planned }.map { it.id })
    }

    @Test
    fun `作ってあるコマンドは実処理を呼ぶ`() {
        val host = FakeCommandHost()
        val commands = Commands.build(host)
        commands["file.save"].run()
        commands["edit.undo"].run()
        commands["view.wrap"].run()
        // 入口も表から生える（バーの欄が直に開くと、表を通らない道が1本できる）。
        commands["view.palette"].run()
        // 行移動は**パレットを行モードで開くだけ** ── 実装を2つ持たない。
        commands["edit.goto"].run()
        assertEquals(
            listOf("save", "undo", "toggleWrap", "openPalette", "openPaletteForLine"),
            host.calls
        )
        assertTrue(host.notYetFor.isEmpty())
    }

    @Test
    fun `E11後半の5つはもう planned でない`() {
        val commands = Commands.build(FakeCommandHost())
        val ids = listOf("edit.comment", "edit.indent", "edit.outdent", "edit.moveLineUp", "edit.moveLineDown")
        for (id in ids) assertFalse("$id がまだ planned", commands[id].planned)
    }

    @Test
    fun `E11後半の5つは表の実処理を呼ぶ`() {
        val host = FakeCommandHost()
        val commands = Commands.build(host)
        commands["edit.comment"].run()
        commands["edit.indent"].run()
        commands["edit.outdent"].run()
        commands["edit.moveLineUp"].run()
        commands["edit.moveLineDown"].run()
        assertEquals(
            listOf("toggleComment", "indent", "outdent", "moveLineUp", "moveLineDown"),
            host.calls
        )
        assertTrue(host.notYetFor.isEmpty())
    }

    // ------------------------------------------------------------------
    // 状態で変わるもの
    // ------------------------------------------------------------------

    @Test
    fun `計測は debug ビルドでだけ表に載る`() {
        val withTrace = Commands.build(FakeCommandHost(trace = true))
        assertNotNull(withTrace.find("debug.trace"))
        assertNotNull(withTrace.find("debug.ime"))

        // release では**灰色で出すのではなく載せない** ── 押せる日が来ないから。
        val withoutTrace = Commands.build(FakeCommandHost(trace = false))
        assertNull(withoutTrace.find("debug.trace"))
        assertNull(withoutTrace.find("debug.ime"))
    }

    @Test
    fun `できないことは available が false になる`() {
        val commands = Commands.build(FakeCommandHost(undoable = false, file = null))
        assertFalse(commands["edit.undo"].available)
        assertFalse(commands["edit.redo"].available)
    }

    @Test
    fun `言語を選ぶは今の言語を説明に出して実処理を呼ぶ`() {
        val host = FakeCommandHost()
        val commands = Commands.build(host)
        assertEquals("今: go", commands["view.language"].detail)
        commands["view.language"].run()
        assertEquals(listOf("chooseLanguage"), host.calls)
    }

    @Test
    fun `文字コードは今の文字コードを説明に出して実処理を呼ぶ`() {
        val host = FakeCommandHost()
        val commands = Commands.build(host)
        assertEquals("今: UTF-8", commands["file.encoding"].detail)
        assertTrue(commands["file.encoding"].available)
        commands["file.encoding"].run()
        assertEquals(listOf("chooseEncoding"), host.calls)
    }

    @Test
    fun `無題のタブでは文字コードを変えられない`() {
        // 読み直すバイトも、書く先も無い。押せる顔にすると押しても何も起きない。
        assertFalse(Commands.build(FakeCommandHost(file = null))["file.encoding"].available)
    }

    @Test
    fun `無題のタブでも保存と名前を付けて保存は押せる`() {
        // **押せない顔にすると、無題のタブに書いたものを保存する道が画面から消える**（U2 の出発点）。
        val host = FakeCommandHost(file = null)
        val commands = Commands.build(host)
        assertTrue(commands["file.save"].available)
        assertTrue(commands["file.saveAs"].available)
        commands["file.save"].run()
        commands["file.saveAs"].run()
        // 無題のときに名前を訊くのは実体の側（`save` の中）。表は口を1本ずつ引くだけ。
        assertEquals(listOf("save", "saveAs"), host.calls)
    }

    @Test
    fun `detail は引くたびに今の状態を返す`() {
        assertEquals("今: 折り返す", Commands.build(FakeCommandHost(wrap = true))["view.wrap"].detail)
        assertEquals("今: 折り返さない", Commands.build(FakeCommandHost(wrap = false))["view.wrap"].detail)
        assertEquals("開いている", Commands.build(FakeCommandHost(drawer = true))["view.drawer"].detail)
        assertEquals("（無題）", Commands.build(FakeCommandHost(file = null))["file.save"].detail)
    }

    @Test
    fun `バーに出す文字は表が持つ`() {
        val commands = Commands.build(FakeCommandHost())
        // 「ファイルツリー」はバーには広すぎる。**意匠の差は表の側で吸収する。**
        assertEquals("☰", commands["view.drawer"].onBar)
        assertEquals("ファイルツリー", commands["view.drawer"].label)
        // 指定が無ければ名前がそのまま出る。
        assertEquals("保存", commands["file.save"].onBar)
    }
}
