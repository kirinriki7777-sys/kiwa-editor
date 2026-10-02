package dev.kirin.kiwa.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 記録の1行が**壊れた JSON にならない**ことを見る。
 *
 * 壊れると解析スクリプトがその行だけ落とすので、**気づかないまま件数が減る**
 * ── 実機ゲートの結論が静かにずれる。
 */
class JsonlFormatTest {

    private fun line(source: String, event: String, vararg fields: Any?) =
        JsonlFormat.line(1, "20260905-190000", "-", 1L, 2L, "main", source, event, fields)

    @Test
    fun fixedFieldsComeFirst() {
        assertEquals(
            """{"seq":1,"sessionId":"20260905-190000","testId":"-","wallTimeMs":1,""" +
                """"elapsedNanos":2,"thread":"main","source":"sora","event":"IC.commitText"}""",
            line("sora", "IC.commitText")
        )
    }

    @Test
    fun typesKeepTheirJsonShape() {
        val s = line("boundary", "scope.begin", "depth", 3, "open", true, "range", null)
        assertTrue(s, s.endsWith(""","depth":3,"open":true,"range":null}"""))
    }

    /** 変換中の文字列には引用符もバックスラッシュも改行も普通に入る。 */
    @Test
    fun quotesAndBackslashesAreEscaped() {
        val s = line("sora", "IC.setComposingText", "text", "a\"b\\c")
        assertTrue(s, s.contains("\"text\":\"a\\\"b\\\\c\""))
    }

    @Test
    fun newlinesAreEscaped() {
        val s = line("sora", "IC.commitText", "text", "a\nb\r\tc")
        assertTrue(s, s.contains("\"text\":\"a\\nb\\r\\tc\""))
    }

    /** IME は素の制御文字も送ってくる。そのまま出すと JSON が壊れる。 */
    @Test
    fun controlCharactersBecomeUnicodeEscapes() {
        val s = line("sora", "IC.commitText", "text", "a\u0001bc")
        assertTrue(s, s.contains("\"text\":\"a\\u0001bc\""))
    }

    /** 日本語とサロゲートペアはそのまま通す（UTF-8 で書くので）。 */
    @Test
    fun japaneseIsNotEscaped() {
        val s = line("sora", "IC.setComposingText", "text", "にほんご")
        assertTrue(s, s.contains("\"text\":\"にほんご\""))
    }

    /** 値が欠けた奇数個のフィールドが来ても、行を壊さず落とすだけ。 */
    @Test
    fun danglingKeyIsDropped() {
        val s = line("boundary", "x", "a", 1, "b")
        assertTrue(s, s.endsWith(""""a":1}"""))
    }
}
