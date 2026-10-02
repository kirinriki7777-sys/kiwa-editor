package dev.kirin.kiwa.ui

import dev.kirin.editoradapter.EditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配色の表の抜け。**選べるのに色が無い、は画面を開くまで分からない**ので機械で見る。
 */
class AppThemeTest {

    @Test
    fun `端末に合わせる以外は全部決まった配色を持つ`() {
        for (choice in ThemeChoice.entries) {
            if (choice == ThemeChoice.SYSTEM) assertNull(choice.fixed) else assertNotNull(choice.name, choice.fixed)
        }
    }

    @Test
    fun `エディタの配色は全部どれかの配色から選べる`() {
        val reachable = ThemeChoice.entries.mapNotNull { it.fixed?.editorTheme }.toSet()
        assertEquals(EditorTheme.entries.toSet(), reachable)
    }

    @Test
    fun `システムバーのアイコンの明暗はエディタの明暗と逆になる`() {
        // 明るい地に明るいアイコンを載せると消える（AppTheme.kt の applySystemBars）
        for (palette in Palette.entries) {
            assertEquals(palette.name, !palette.editorTheme.isDark, palette.lightSystemBars)
        }
    }

    @Test
    fun `選べる配色は色違い8つで暗い4つと明るい4つ`() {
        // 「明るい」「生成り」「暗い」は 2026-10-02 に外した。**残っているのは端末に合わせると色違い8つだけ。**
        val fixed = ThemeChoice.entries.filter { it != ThemeChoice.SYSTEM }
        assertEquals(8, fixed.size)
        assertEquals(4, fixed.count { it.fixed!!.editorTheme.isDark })
    }

    @Test
    fun `名前は呼び名に Dark か Light が付く`() {
        for (choice in ThemeChoice.entries) {
            if (choice == ThemeChoice.SYSTEM) continue
            val expected = if (choice.fixed!!.editorTheme.isDark) "（Dark）" else "（Light）"
            assertTrue(choice.label, choice.label.endsWith(expected))
        }
    }

    @Test
    fun `外した名前は読み替え、知らない名前は null`() {
        assertEquals(ThemeChoice.SUMI, ThemeChoice.fromStored("DARK"))
        assertEquals(ThemeChoice.SHIRO, ThemeChoice.fromStored("LIGHT"))
        assertEquals(ThemeChoice.KINARI, ThemeChoice.fromStored("CREAM"))
        assertEquals(ThemeChoice.YORU, ThemeChoice.fromStored("YORU"))
        assertNull(ThemeChoice.fromStored("NEON"))
        assertNull(ThemeChoice.fromStored(""))
    }

    @Test
    fun `既定は暗い配色`() {
        // 開いた瞬間に真っ白で眩しい、が最初の不満だった（AppTheme.kt の ThemeChoice）。
        assertTrue(ThemeChoice.DEFAULT.fixed!!.editorTheme.isDark)
    }
}
