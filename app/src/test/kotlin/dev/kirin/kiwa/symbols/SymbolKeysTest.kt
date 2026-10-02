package dev.kirin.kiwa.symbols

import dev.kirin.kiwa.command.Commands
import dev.kirin.kiwa.command.FakeCommandHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 記号キー列の表そのものの試験（E12）。**画面も実機も要らない部分**をここで押さえる。
 *
 * 一番効くのは**集合の一致**を見ている2本 ──
 *
 *   1. 既定の並びの id が全部 [SymbolKeys.ALL] に在る
 *   2. 列に載せたコマンドの id が全部 `Commands` の表に在る
 *
 * どちらも「件数が合っている」ではなく**名前で突き合わせる**。件数で見ると、
 * id を1文字打ち間違えた行が黙って通り、**画面では「そのキーだけ出ない」**という
 * 見つけにくい形で出る。
 */
class SymbolKeysTest {

    private val ids = SymbolKeys.ALL.map { it.id }

    // ------------------------------------------------------------------
    // 表の形
    // ------------------------------------------------------------------

    @Test
    fun `id が重複していない`() {
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `既定の並びは全部実在する`() {
        val unknown = SymbolKeys.DEFAULT.filterNot { it in ids }
        assertEquals(emptyList<String>(), unknown)
    }

    @Test
    fun `既定の並びは期待するキー列と同じ`() {
        // 狭い場所でも操作名を読めるよう、「戻す」「進む」は記号でなく文字で表示する。
        assertEquals(
            listOf(
                "Ctrl", "Shift",
                "Tab", "Esc", "←", "→", "↑", "↓",
                "保存", "検索", "戻す", "進む",
                "{", "}", "(", ")", "[", "]", "=", ";", "\"", "|"
            ),
            SymbolKeys.parse(SymbolKeys.DEFAULT).map { it.label }
        )
    }

    @Test
    fun `コマンドの控えラベルは表の短い名前と同じ`() {
        // **設定画面は表を引けない**（`Commands.build` に host が要る）ので控えを持っているが、
        // そこが表とずれると「設定で選んだ名前」と「列に出る名前」が別物になる。
        val commands = Commands.build(FakeCommandHost())
        for (key in SymbolKeys.ALL) {
            if (key.kind != SymbolKind.COMMAND) continue
            val command = commands.find(key.commandId ?: "") ?: continue
            assertEquals(key.id, command.onBar, key.label)
        }
    }

    @Test
    fun `種類ごとに持っているものが埋まっている`() {
        for (key in SymbolKeys.ALL) {
            when (key.kind) {
                SymbolKind.MODIFIER -> {
                    assertNotNull(key.id, key.modifier)
                    assertEquals(key.id, 0, key.keyCode)
                }
                SymbolKind.RAW -> {
                    // **キーコードが 0 だと「何も押していない」と同じ**になり、
                    // 押しても何も起きないボタンが並ぶ。
                    assertTrue(key.id, key.keyCode != 0)
                }
                SymbolKind.COMMAND -> {
                    assertNotNull(key.id, key.commandId)
                    assertEquals(key.id, 0, key.keyCode)
                }
                SymbolKind.TEXT -> {
                    assertNotNull(key.id, key.text)
                    assertEquals(key.id, key.label, key.text)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // ★ 操作の表との突き合わせ
    // ------------------------------------------------------------------

    @Test
    fun `列に載せたコマンドは全部 操作の表に在る`() {
        val commands = Commands.build(FakeCommandHost())
        val missing = SymbolKeys.ALL
            .filter { it.kind == SymbolKind.COMMAND }
            .mapNotNull { it.commandId }
            .filter { commands.find(it) == null }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `計測のコマンドは列に載せていない`() {
        // debug ビルドにしか無いものを載せると、release で「押しても何もしないボタン」になる。
        val commands = Commands.build(FakeCommandHost(trace = false))
        val missing = SymbolKeys.ALL
            .filter { it.kind == SymbolKind.COMMAND }
            .mapNotNull { it.commandId }
            .filter { commands.find(it) == null }
        assertEquals(emptyList<String>(), missing)
    }

    // ------------------------------------------------------------------
    // 読み書き
    // ------------------------------------------------------------------

    @Test
    fun `知らない id は落とす`() {
        val keys = SymbolKeys.parse(listOf("mod.ctrl", "raw.nope", "text.{"))
        assertEquals(listOf("mod.ctrl", "text.{"), keys.map { it.id })
    }

    @Test
    fun `空の並びは空のまま`() {
        assertEquals(emptyList<SymbolKey>(), SymbolKeys.parse(emptyList()))
    }

    @Test
    fun `並びは書いてある順のまま`() {
        val given = listOf("text.{", "mod.ctrl", "raw.tab")
        assertEquals(given, SymbolKeys.parse(given).map { it.id })
    }

    @Test
    fun `決まった順へ並べ直せる`() {
        val jumbled = listOf("text.{", "mod.shift", "raw.tab", "mod.ctrl")
        assertEquals(
            listOf("mod.ctrl", "mod.shift", "raw.tab", "text.{"),
            SymbolKeys.inCanonicalOrder(jumbled)
        )
    }

    @Test
    fun `決まった順へ並べ直すとき知らない id は落ちる`() {
        assertEquals(listOf("mod.ctrl"), SymbolKeys.inCanonicalOrder(listOf("mod.ctrl", "raw.nope")))
    }

    @Test
    fun `知らない id は引けない`() {
        assertNull(SymbolKeys.of("raw.nope"))
    }
}
