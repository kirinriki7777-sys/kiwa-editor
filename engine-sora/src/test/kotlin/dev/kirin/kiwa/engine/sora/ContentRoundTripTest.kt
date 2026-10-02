package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.text.Content
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **ファイルを開いて保存したとき、触っていない行のバイトが変わらないこと**を確かめる。
 *
 * 読解では、Sora は改行コードを行ごとに持ち（`text/ContentLine.java:50`）、
 * 取り込み時に解釈して（`text/Content.java:418`）、`toString()` で書き戻す
 * （`text/Content.java:1002`）── ので**アプリ側で正規化してはいけない**、と読めた。
 * 正規化して保存すると、CRLF のファイルが LF に化けて全行 diff が出る。
 *
 * 読んだだけでは信じない。ここで往復させる。
 */
class ContentRoundTripTest {

    private fun roundTrip(text: String) {
        assertEquals(text, Content(text).toString())
    }

    @Test
    fun lf() = roundTrip("a\nb\nc\n")

    @Test
    fun crlf() = roundTrip("a\r\nb\r\nc\r\n")

    @Test
    fun cr() = roundTrip("a\rb\rc\r")

    /** 混在は「直して」しまうと元に戻らなくなる。行ごとに持つ設計ならそのまま返るはず。 */
    @Test
    fun mixed() = roundTrip("lf\ncrlf\r\ncr\rend")

    /** 末尾の改行の有無は diff にそのまま出る。 */
    @Test
    fun noTrailingNewline() = roundTrip("a\nb")

    @Test
    fun emptyFile() = roundTrip("")

    @Test
    fun japaneseAndSurrogatePairs() = roundTrip("日本語\r\n𩸽と😀\nおわり")
}
