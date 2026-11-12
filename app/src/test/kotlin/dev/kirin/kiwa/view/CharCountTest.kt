package dev.kirin.kiwa.view

import org.junit.Assert.assertEquals
import org.junit.Test

class CharCountTest {

    @Test
    fun 空は0() = assertEquals(0, CharCount.count(""))

    @Test
    fun 改行だけは0() = assertEquals(0, CharCount.count("\n\n\r\n\r"))

    @Test
    fun 半角空白と全角空白とタブは数えない() = assertEquals(2, CharCount.count("あ 　\tい"))

    @Test
    fun CRLFは改行2つぶんを数えない() = assertEquals(4, CharCount.count("ab\r\ncd"))

    @Test
    fun 絵文字のサロゲート対は1文字() {
        assertEquals(1, CharCount.count("😀"))
        assertEquals(3, CharCount.count("a😀b"))
    }

    @Test
    fun 壊れたサロゲートも落ちずに1文字() = assertEquals(1, CharCount.count("\uD83D"))

    @Test
    fun 選択範囲だけを数える() {
        val text = "いろは\nにほへと"
        assertEquals(3, CharCount.count(text, 0, 3))
        // 改行をまたぐ選択は、改行を数えない。
        assertEquals(4, CharCount.count(text, 2, 7))
        assertEquals(7, CharCount.count(text))
    }

    @Test
    fun 範囲は本文の外へはみ出しても落ちない() {
        assertEquals(2, CharCount.count("ab", -5, 99))
        assertEquals(0, CharCount.count("ab", 2, 1))
    }

    @Test
    fun 変換中の未確定の文字は数えない() {
        // 「abc」の後ろの「にほんご」（3..6）が変換中。
        val text = "abcにほんごいろは"
        assertEquals(6, CharCount.count(text, exclude = 3..6))
        assertEquals(10, CharCount.count(text))
    }

    @Test
    fun 選択が変換中にかかっても未確定ぶんは引く() {
        val text = "abcにほんご"
        assertEquals(3, CharCount.count(text, 0, 7, exclude = 3..6))
    }
}
