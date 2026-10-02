package dev.kirin.kiwa.engine.sora

import dev.kirin.editoradapter.EditorTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EditorTheme] と同梱テーマの対応に抜けが無いか。
 *
 * 抜けると `TextMateSetup.colorScheme` の `getValue` で落ちる。ファイルが無いと
 * **例外は出ずに警告1行で Sora 同梱の配色へ落ちる**ので、画面を見ないと分からない。
 */
class TextMateThemesTest {

    private fun themeFile(name: String) = File("src/main/assets/textmate/$name.json")

    @Test
    fun `全部の配色に同梱テーマがある`() {
        assertEquals(EditorTheme.entries.toSet(), TextMateSetup.themes.keys)
        for ((theme, name) in TextMateSetup.themes) {
            assertTrue("$theme → $name.json が無い", themeFile(name).isFile)
        }
    }

    @Test
    fun `どの配色も地と文字の色を持ち、明暗の申告が配色と一致する`() {
        for (theme in EditorTheme.entries) {
            val text = themeFile(TextMateSetup.themes.getValue(theme)).readText()
            assertTrue("$theme", "\"editor.background\"" in text)
            assertTrue("$theme", "\"editor.foreground\"" in text)
            assertTrue("$theme", "\"tokenColors\"" in text)
            val type = if (theme.isDark) "\"type\": \"dark\"" else "\"type\": \"light\""
            assertTrue("$theme の明暗", type in text)
        }
    }

    @Test
    fun `外した配色のテーマは同梱していない`() {
        // 2026-10-02 に選べる配色から外した3枚。**残すと誰も使わない 40KB が毎回読まれる**（テーマは先に全部読む）。
        for (name in listOf("darcula", "quietlight", "cream")) {
            assertFalse("$name.json が残っている", themeFile(name).exists())
        }
    }
}
