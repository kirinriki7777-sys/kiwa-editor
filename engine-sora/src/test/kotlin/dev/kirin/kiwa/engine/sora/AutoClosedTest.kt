package dev.kirin.kiwa.engine.sora

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自動で入れた閉じを踏み越す条件（2026-10-02）。
 *
 * **踏み越しすぎると打った文字が消える**ので、覚えた位置がずれたら必ず忘れる側へ倒す。
 */
class AutoClosedTest {

    @Test
    fun `覚えた位置でだけ1回踏み越す`() {
        val closed = AutoClosed()
        closed.add(5)
        assertFalse(closed.consume(4))
        assertTrue(closed.consume(5))
        assertFalse(closed.consume(5))
    }

    @Test
    fun `括弧の中に打つと閉じは後ろへずれる`() {
        // `write(|)` で `"hello"` の7文字を打った ── `)` は 6 から 13 へ
        val closed = AutoClosed()
        closed.add(6)
        closed.onInsert(6, 7)
        assertFalse(closed.consume(6))
        assertTrue(closed.consume(13))
    }

    @Test
    fun `閉じより後ろへの挿入ではずれない`() {
        val closed = AutoClosed()
        closed.add(6)
        closed.onInsert(7, 3)
        assertTrue(closed.consume(6))
    }

    @Test
    fun `閉じそのものが消えたら忘れる`() {
        // `(|)` で Backspace ── 組ごと消える（BracketPairs.deletesBoth）
        val closed = AutoClosed()
        closed.add(1)
        closed.onDelete(0, 2)
        assertFalse(closed.consume(0))
        assertFalse(closed.consume(1))
    }

    @Test
    fun `前が消えたら前へずれる`() {
        val closed = AutoClosed()
        closed.add(10)
        closed.onDelete(2, 5)
        assertTrue(closed.consume(7))
    }

    @Test
    fun `入れ子でも1つずつ踏み越す`() {
        // `f((|))` ── 内側の `)` が 4、外側が 5
        val closed = AutoClosed()
        closed.add(3)
        closed.onInsert(3, 2)
        closed.add(4)
        assertTrue(closed.consume(4))
        assertTrue(closed.consume(5))
    }

    @Test
    fun `行を離れたら忘れる`() {
        val closed = AutoClosed()
        closed.add(3)
        closed.add(40)
        closed.keepOnly { it < 10 }
        assertTrue(closed.consume(3))
        assertFalse(closed.consume(40))
    }

    @Test
    fun `clear で全部忘れる`() {
        val closed = AutoClosed()
        closed.add(3)
        closed.clear()
        assertTrue(closed.isEmpty)
    }
}
