package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.span.SpanColorResolver
import io.github.rosemoe.sora.lang.styling.span.SpanExtAttrs
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 変換中の装飾を構文スパンへ重ねる部分の試験。
 *
 * **実機でしか測れないのは「見えるか」だけ**で、重ねの組み立ては
 * ここで確かめられる ── 何本のスパンに割れて、どこが背景を持ち、
 * 元の色が残っているか。E5 でここを取り違えると、
 * 実機では「色が変」としか見えず切り分けに1往復かかる。
 */
class ComposingOverlayTest {

    private val scheme = EditorColorScheme()

    private val teal = 0x66008080.toInt()
    private val faint = 0x19008080

    // ------------------------------------------------------------------
    // 重ねが無いとき
    // ------------------------------------------------------------------

    @Test
    fun `重ねが無ければ元の列をそのまま返す`() {
        val base = listOf(span(0, 11), span(4, 22))
        assertSame(base, ComposingOverlay.merge(base, emptyList(), 10))
    }

    // ------------------------------------------------------------------
    // 行の途中
    // ------------------------------------------------------------------

    @Test
    fun `行の途中の範囲は3つに割れて真ん中だけ背景を持つ`() {
        val base = listOf(span(0, 11))
        val merged = ComposingOverlay.merge(base, listOf(range(0, 2, 5, teal)), 10)

        assertEquals(listOf(0, 2, 5), merged.map { it.column })
        assertNull(background(merged[0]))
        assertEquals(teal, background(merged[1]))
        assertNull(background(merged[2]))
    }

    @Test
    fun `元のスパンの色は装飾を重ねても残る`() {
        val base = listOf(span(0, 11), span(4, 22))
        val merged = ComposingOverlay.merge(base, listOf(range(0, 2, 6, teal)), 10)

        // 桁 0-2 は 11、2-4 は 11（装飾つき）、4-6 は 22（装飾つき）、6- は 22。
        assertEquals(listOf(0, 2, 4, 6), merged.map { it.column })
        assertEquals(listOf(11L, 11L, 22L, 22L), merged.map { it.style })
        assertEquals(listOf(null, teal, teal, null), merged.map { background(it) })
    }

    // ------------------------------------------------------------------
    // 文節の塗り分け
    // ------------------------------------------------------------------

    @Test
    fun `隣り合う2つの範囲は別々の色のまま残る`() {
        val base = listOf(span(0, 11))
        val merged = ComposingOverlay.merge(
            base,
            listOf(range(0, 0, 3, teal), range(0, 3, 6, faint)),
            10
        )

        assertEquals(listOf(0, 3, 6), merged.map { it.column })
        assertEquals(teal, background(merged[0]))
        assertEquals(faint, background(merged[1]))
        assertNull(background(merged[2]))
    }

    // ------------------------------------------------------------------
    // 端
    // ------------------------------------------------------------------

    @Test
    fun `行末まで届く範囲は桁数と同じ位置に空のスパンを作らない`() {
        val base = listOf(span(0, 11))
        val merged = ComposingOverlay.merge(base, listOf(range(0, 3, 6, teal)), 6)

        // 桁 6 は行末なので切れ目を入れない ── 入れると幅 0 のスパンが1本増える。
        assertEquals(listOf(0, 3), merged.map { it.column })
        assertEquals(teal, background(merged[1]))
    }

    @Test
    fun `行頭から始まる範囲は桁0のスパンが装飾を持つ`() {
        val base = listOf(span(0, 11))
        val merged = ComposingOverlay.merge(base, listOf(range(0, 0, 4, teal)), 10)

        assertEquals(listOf(0, 4), merged.map { it.column })
        assertEquals(teal, background(merged[0]))
        assertNull(background(merged[1]))
    }

    // ------------------------------------------------------------------
    // 解析器が何も作っていない行
    // ------------------------------------------------------------------

    @Test
    fun `元のスパンが空でも装飾は出る`() {
        // 言語を付けていない、または解析がまだ走っていない行。
        // **ここで諦めると「拡張子の分からないファイルだけ装飾が出ない」になる。**
        val merged = ComposingOverlay.merge(emptyList(), listOf(range(0, 0, 3, teal)), 8)

        assertEquals(listOf(0, 3), merged.map { it.column })
        assertEquals(teal, background(merged[0]))
        assertNull(background(merged[1]))
    }

    // ------------------------------------------------------------------
    // 皮の側 ── 表示行が1行のときに落とされないこと
    // ------------------------------------------------------------------

    @Test
    fun `重ねがある行は元が空でもスパン件数を0と申告しない`() {
        // 描画側は getSpanCount() == 0 を見て EmptyReader へ差し替える
        // （widget/EditorRenderer.java:1391）。そこで 0 を返すと重ねごと捨てられる。
        val overlay = ComposingOverlay().apply { set(listOf(range(0, 0, 3, teal))) }
        val spans = ComposingOverlaySpans(null, overlay) { 8 }

        val reader = spans.read()
        reader.moveToLine(0)
        assertTrue(reader.spanCount > 0)
        assertEquals(teal, background(reader.getSpansOnLine(0)[0]))
    }

    @Test
    fun `重ねの無い行は元の読み手へ素通しする`() {
        val overlay = ComposingOverlay().apply { set(listOf(range(3, 0, 3, teal))) }
        val spans = ComposingOverlaySpans(null, overlay) { 8 }

        val reader = spans.read()
        reader.moveToLine(0)
        // 元が無く重ねも無いので件数は 0。これは欠陥ではなく、
        // 描画側がそこで EmptyReader へ差し替える（widget/EditorRenderer.java:1391）
        // ── Sora が言語なしのときに通る道と同じものへ戻る。
        assertEquals(0, reader.spanCount)
        assertNull(background(reader.getSpansOnLine(0)[0]))
    }

    @Test
    fun `重ねを消すと元へ戻る`() {
        val overlay = ComposingOverlay().apply { set(listOf(range(0, 0, 3, teal))) }
        val spans = ComposingOverlaySpans(null, overlay) { 8 }

        overlay.clear()

        val reader = spans.read()
        reader.moveToLine(0)
        assertNull(background(reader.getSpansOnLine(0)[0]))
    }

    // ------------------------------------------------------------------

    private fun span(column: Int, style: Long): Span = Span.obtain(column, style)

    private fun range(line: Int, from: Int, to: Int, color: Int) =
        ComposingOverlay.Range(line, from, to, color)

    /** そのスパンに重ねの背景が入っているか。入っていなければ null。 */
    private fun background(span: Span): Int? {
        val resolver = span.getSpanExt<SpanColorResolver>(SpanExtAttrs.EXT_COLOR_RESOLVER) ?: return null
        val color = resolver.getBackgroundColor(span) ?: return null
        assertTrue("重ねの色は固定色で入る", color is ConstColor)
        return color.resolve(scheme)
    }
}
