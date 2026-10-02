package dev.kirin.kiwa.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 保存の後始末（行末の空白 / 最後の改行）。**画面を出さずに踏める側**を全部見る。
 *
 * 見たいのは3つ ── 消すのは行末の半角空白とタブだけ、改行コードは元のまま、
 * そして置き換えが1か所にまとまる（Undo が1回で戻る）こと。
 */
class SaveCleanupTest {

    private fun clean(text: String, trim: Boolean = true, finalNewline: Boolean = false, separator: String = "\n") =
        SaveCleanup.clean(text, trim, finalNewline, separator)

    @Test
    fun `行末の空白とタブを消す`() {
        assertEquals("a\nb\n\nc", clean("a  \nb\t\n \t\nc   "))
    }

    @Test
    fun `改行コードは行ごとに元のまま`() {
        // CRLF と LF が混ざったファイルでも、改行そのものには触らない（`TextFile` の的）。
        assertEquals("a\r\nb\nc\rd", clean("a \r\nb\t\nc  \rd "))
    }

    @Test
    fun `全角の空白は残す`() {
        // 日本語の文で意図して置くことがある。
        assertEquals("見出し　\nx", clean("見出し　 \nx"))
    }

    @Test
    fun `行の途中の空白は残す`() {
        assertEquals("a  b\n\tc", clean("a  b \n\tc"))
    }

    @Test
    fun `最後に改行が無ければファイルの流儀で足す`() {
        assertEquals("a\r\nb\r\n", clean("a\r\nb", trim = false, finalNewline = true, separator = "\r\n"))
        assertEquals("a\n", clean("a\n", trim = false, finalNewline = true))
        assertEquals("a\r", clean("a\r", trim = false, finalNewline = true))
    }

    @Test
    fun `空のファイルには改行を足さない`() {
        assertEquals("", clean("", trim = false, finalNewline = true))
    }

    @Test
    fun `両方切っていれば何も変えない`() {
        val text = "a  \nb"
        assertEquals(text, clean(text, trim = false, finalNewline = false))
    }

    @Test
    fun `置き換えは変わった範囲だけの1か所`() {
        val text = "keep\nfix  \nkeep\nfix\t\nkeep"
        val cleaned = clean(text)
        val edit = SaveCleanup.edit(text, cleaned)!!
        // 1行目と最後の行には触れない。
        assertEquals(8, edit.start)
        assertEquals(text.length - "\nkeep".length, edit.end)
        val applied = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
        assertEquals(cleaned, applied)
    }

    @Test
    fun `変わらなければ置き換えは無い`() {
        assertNull(SaveCleanup.edit("abc", "abc"))
    }

    @Test
    fun `行と桁から位置を引き、桁が長ければ行末へ寄せる`() {
        val text = "ab\r\ncde\nf"
        assertEquals(0, SaveCleanup.indexOf(text, 0, 0))
        assertEquals(2, SaveCleanup.indexOf(text, 0, 9))
        assertEquals(5, SaveCleanup.indexOf(text, 1, 1))
        assertEquals(7, SaveCleanup.indexOf(text, 1, 5))
        assertEquals(9, SaveCleanup.indexOf(text, 2, 1))
        // 行が無ければ末尾。
        assertEquals(text.length, SaveCleanup.indexOf(text, 9, 0))
    }
}
