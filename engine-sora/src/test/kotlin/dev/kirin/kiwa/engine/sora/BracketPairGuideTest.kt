package dev.kirin.kiwa.engine.sora

import dev.kirin.kiwa.engine.sora.BracketPairGuide.Horizontal
import dev.kirin.kiwa.engine.sora.BracketPairGuide.Vertical
import org.junit.Assert.assertEquals
import org.junit.Test

/** 組の線の形。描画は実機でしか見えないので、**どの線がどこに出るか**はここで押さえる。 */
class BracketPairGuideTest {

    @Test
    fun `同じ行の組は線なし`() {
        assertEquals(emptyList<BracketPairGuide.Segment>(), BracketPairGuide.segments(3, 4, 3, 9, 0))
    }

    @Test
    fun `本文を挟む組は縦線と上の横線`() {
        // 0: `fn main() {`   開き = 桁 10、字下げ 0
        // 1: `    body`
        // 2: `}`            閉じ = 桁 0（字下げと同じなので下の横線は無い）
        assertEquals(
            listOf(Horizontal(0, atBottom = true, fromColumn = 0, toColumn = 10), Vertical(0, 1, 2)),
            BracketPairGuide.segments(0, 10, 2, 0, 0)
        )
    }

    @Test
    fun `縦線は開きの次の行から閉じの行の上端まで、開きの行の字下げの桁`() {
        // 4: `    if (x) {`  字下げ 4
        // 5, 6: 本文
        // 7: `    }`
        assertEquals(
            listOf(Horizontal(4, true, 4, 12), Vertical(4, 5, 7)),
            BracketPairGuide.segments(4, 12, 7, 4, 4)
        )
    }

    @Test
    fun `閉じが字下げより右にある時だけ下の横線`() {
        // 0: `call(a,`
        // 1: `     b)`  閉じ = 桁 6、字下げ 0
        assertEquals(
            listOf(Horizontal(0, true, 0, 4), Horizontal(1, false, 0, 6)),
            BracketPairGuide.segments(0, 4, 1, 6, 0)
        )
    }

    @Test
    fun `次の行に閉じがあれば縦線は長さが無いので出さない`() {
        assertEquals(
            listOf(Horizontal(0, true, 2, 8)),
            BracketPairGuide.segments(0, 8, 1, 2, 2)
        )
    }

    @Test
    fun `開きが字下げの位置にあれば上の横線は無い`() {
        // 0: `{`   1: `  x`   2: `}`
        assertEquals(listOf(Vertical(0, 1, 2)), BracketPairGuide.segments(0, 0, 2, 0, 0))
    }

    @Test
    fun `タブの字下げはタブ幅で数える`() {
        assertEquals(0, BracketPairGuide.indentColumns("foo", 4))
        assertEquals(4, BracketPairGuide.indentColumns("    foo", 4))
        assertEquals(4, BracketPairGuide.indentColumns("\tfoo", 4))
        assertEquals(8, BracketPairGuide.indentColumns("\t\tfoo", 4))
        assertEquals(6, BracketPairGuide.indentColumns("\t  foo", 4))
        assertEquals(2, BracketPairGuide.indentColumns("\t\tfoo", 1))
        assertEquals(3, BracketPairGuide.indentColumns("   ", 4)) // 空白だけの行は全部が字下げ
    }

    @Test
    fun `タブを含む行の桁と文字の位置を行き来できる`() {
        val line = "\tif (x) {"
        // `{` は文字として 8 番目。タブ幅 4 なら表示の桁は 11
        assertEquals(11, BracketPairGuide.displayColumn(line, 8, 4))
        assertEquals(8, BracketPairGuide.charIndexAt(line, 11, 4))
        // タブの途中の桁は、そのタブの次の文字
        assertEquals(1, BracketPairGuide.charIndexAt(line, 2, 4))
        // 行より右は行末
        assertEquals(line.length, BracketPairGuide.charIndexAt(line, 99, 4))
    }
}
