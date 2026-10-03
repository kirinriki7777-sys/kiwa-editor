package dev.kirin.kiwa.ui

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 8つの配色 json の「括弧の組・今の縦線・固定見出し」の色が、アプリ側の [Palette] と食い違っていないか。
 *
 * json は engine-sora が持ち、強調色（`accent`）と枠（`frame`）は :app の [Palette] が持つ ──
 * 片方だけ直すと、画面の枠は新しい色なのに本文の括弧だけ古い色、になる。色の見え方は実機でしか分からない。
 */
class EditorThemeColorsTest {

    private fun colors(palette: Palette): JSONObject {
        val file = File("../engine-sora/src/main/assets/textmate/${palette.name.lowercase()}.json")
        assertNotNull("${palette.name} の json が無い", file.takeIf { it.isFile })
        return JSONObject(file.readText()).getJSONObject("colors")
    }

    private fun rgb(color: Int) = "#%06x".format(color and 0xFFFFFF)

    @Test
    fun `どの配色も括弧の組と縦線の色が強調色で、固定見出しの区切りが枠の色`() {
        for (palette in Palette.entries) {
            val colors = colors(palette)
            val accent = rgb(palette.accent)
            val name = palette.name
            assertEquals("$name background", accent + "38", colors.getString("editorBracketMatch.background"))
            assertEquals("$name border", accent, colors.getString("editorBracketMatch.border"))
            assertEquals("$name foreground", accent, colors.getString("editorBracketMatch.foreground"))
            assertEquals("$name pair guide", accent, colors.getString("editorBracketPairGuide.activeBackground1"))
            assertEquals("$name 今の縦線", accent, colors.getString("editorIndentGuide.activeBackground"))
            assertEquals("$name sticky", rgb(palette.frame), colors.getString("editorStickyScroll.border"))
        }
    }
}
