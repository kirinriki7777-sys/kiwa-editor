package dev.kirin.kiwa.palette

import dev.kirin.kiwa.palette.PaletteQuery.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 頭の1文字がモードを決める（案C / `fresh` と同じ形）。 */
class PaletteQueryTest {

    @Test
    fun `無印はファイル`() {
        assertEquals(PaletteQuery(Mode.FILE, "main"), PaletteQuery.parse("main"))
        assertEquals(PaletteQuery(Mode.FILE, ""), PaletteQuery.parse(""))
    }

    @Test
    fun `大なりはコマンド`() {
        assertEquals(PaletteQuery(Mode.COMMAND, "save"), PaletteQuery.parse(">save"))
        // 打った後に空白を入れても同じ ── prefix は1文字で、後ろは語。
        assertEquals(PaletteQuery(Mode.COMMAND, "save"), PaletteQuery.parse("> save"))
        assertEquals(PaletteQuery(Mode.COMMAND, ""), PaletteQuery.parse(">"))
    }

    @Test
    fun `コロンは行`() {
        assertEquals(PaletteQuery(Mode.LINE, "42"), PaletteQuery.parse(":42"))
        assertEquals(42, PaletteQuery.parse(":42").lineNumber())
    }

    @Test
    fun `行になっていないうちは行番号を返さない`() {
        assertNull(PaletteQuery.parse(":").lineNumber())
        assertNull(PaletteQuery.parse(":abc").lineNumber())
        assertNull(PaletteQuery.parse(":4x").lineNumber())
        assertNull(PaletteQuery.parse(":0").lineNumber())
        // 行モードでなければ数字でも行にしない（`42` はファイル名かもしれない）。
        assertNull(PaletteQuery.parse("42").lineNumber())
    }

    @Test
    fun `全角の数字でも行になる`() {
        // **日本語 IME を通すと全角で来ることがある。** 打てたのに何も起きないと、
        // 打った側からは理由が見えない。
        assertEquals(42, PaletteQuery.parse(":４２").lineNumber())
        assertEquals(7, PaletteQuery.parse(":７").lineNumber())
    }

    @Test
    fun `全角の prefix でも同じモードになる`() {
        // **実機（2026-09-07）**: 日本語 IME で `>` を打つと全角の「＞」が入った。
        // 半角だけを見ていると、打てているのに何も変わらない。
        assertEquals(Mode.COMMAND, PaletteQuery.parse("＞save").mode)
        assertEquals(Mode.LINE, PaletteQuery.parse("：42").mode)
        assertEquals(42, PaletteQuery.parse("：４２").lineNumber())
    }

    @Test
    fun `モードは自分の prefix を持つ`() {
        // 押して移る道（モードの帯）が同じ prefix から作られる ── 2箇所に書かない。
        assertEquals("", Mode.FILE.prefix)
        assertEquals(Mode.COMMAND, PaletteQuery.parse(Mode.COMMAND.prefix + "x").mode)
        assertEquals(Mode.LINE, PaletteQuery.parse(Mode.LINE.prefix + "1").mode)
    }

    @Test
    fun `ファイル名の中の記号はモードを変えない`() {
        // prefix は**先頭の1文字だけ**。途中の `:` や `>` はただの文字。
        assertEquals(PaletteQuery(Mode.FILE, "a:b"), PaletteQuery.parse("a:b"))
        assertEquals(PaletteQuery(Mode.COMMAND, "a>b"), PaletteQuery.parse(">a>b"))
    }
}
