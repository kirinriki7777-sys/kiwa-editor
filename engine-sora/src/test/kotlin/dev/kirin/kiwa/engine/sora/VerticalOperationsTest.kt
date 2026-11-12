package dev.kirin.kiwa.engine.sora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalOperationsTest {
    private fun layout(text: String, height: Float = 300f) = VerticalLayout.build(
        verticalParagraphsOf(text), 20f, 30f, 300f, height, 10f, 10f, 10f, 10f,
        { VerticalOrientation.U }, { false }, { (0..it.length).toSet() }, { 20f }
    )

    private fun move(drag: VerticalTouchSelection, layout: VerticalLayout, offset: Int): Pair<Int, Int> {
        val point = layout.cursorPosition(offset)
        return drag.drag(layout, point.centerX, point.y, 0f)
    }

    @Test
    fun `T1_末尾を先と手前へ引いても先頭は動かない`() {
        val layout = layout("甲乙丙丁戊己庚辛")
        val drag = VerticalTouchSelection(false, 2, 5)
        assertEquals(2 to 7, move(drag, layout, 7))
        assertEquals(2 to 3, move(drag, layout, 3))
        assertEquals(2, drag.anchor)
    }

    @Test
    fun `T1_先頭を先と手前へ引いても末尾は動かない`() {
        val layout = layout("甲乙丙丁戊己庚辛")
        val drag = VerticalTouchSelection(true, 2, 5)
        assertEquals(4 to 5, move(drag, layout, 4))
        assertEquals(0 to 5, move(drag, layout, 0))
        assertEquals(5, drag.anchor)
    }

    @Test
    fun `T1_固定端を越えると先頭末尾が入れ替わり戻せる`() {
        val layout = layout("甲乙丙丁戊己庚辛")
        val end = VerticalTouchSelection(false, 2, 5)
        assertEquals(1 to 2, move(end, layout, 1))
        assertEquals(2 to 6, move(end, layout, 6))
        val start = VerticalTouchSelection(true, 2, 5)
        assertEquals(5 to 7, move(start, layout, 7))
        assertEquals(3 to 5, move(start, layout, 3))
    }

    @Test
    fun `T1_重なった時は直前の非空の選択を保つ`() {
        val layout = layout("甲乙丙丁戊己庚辛")
        val drag = VerticalTouchSelection(false, 2, 5)
        assertEquals(2 to 5, move(drag, layout, 2))
        assertEquals(1 to 2, move(drag, layout, 1))
        assertEquals(1 to 2, move(drag, layout, 2))
    }

    @Test
    fun `T1_指からマスとCRLFの途中へは入らずスクロール後も同じ位置を引ける`() {
        val layout = layout("甲か\u309912\r\n𠮷乙")
        val drag = VerticalTouchSelection(false, 0, 1)
        val first = layout.columns[0]
        for (glyph in first.glyphs) {
            val range = drag.drag(layout, first.centerX + 90f, glyph.centerY + 1f, 90f)
            assertEquals(glyph.endOffset, range.second)
        }
        assertEquals(0 to 5, drag.drag(layout, first.centerX, 299f, 0f))
        val second = layout.columns[1]
        assertEquals(0 to 9, drag.drag(layout, second.centerX, second.glyphs.first().centerY + 1f, 0f))
    }

    @Test
    fun `T2_一列のつまみは最初のマスの上辺と最後の下辺`() {
        val layout = layout("甲乙丙丁戊己庚辛", 100f)
        assertEquals(VerticalHandlePoint(260f, 30f), layout.selectionHandle(1, true))
        assertEquals(VerticalHandlePoint(260f, 70f), layout.selectionHandle(3, false))
    }

    @Test
    fun `T2_折り返しをまたぐ選択とちょうど列末尾で終わる選択`() {
        val layout = layout("甲乙丙丁戊己庚辛", 100f)
        assertEquals(VerticalHandlePoint(260f, 30f), layout.selectionHandle(1, true))
        assertEquals(VerticalHandlePoint(230f, 50f), layout.selectionHandle(6, false))
        assertEquals(VerticalHandlePoint(260f, 90f), layout.selectionHandle(4, false))
        assertEquals(VerticalHandlePoint(260f, 10f), layout.selectionHandle(4, true, 30f))
    }

    @Test
    fun `T2_段落をまたぐ選択と改行だけの選択`() {
        val layout = layout("甲乙\n丙丁\n\n戊")
        assertEquals(VerticalHandlePoint(260f, 30f), layout.selectionHandle(1, true))
        assertEquals(VerticalHandlePoint(230f, 50f), layout.selectionHandle(5, false))
        assertEquals(VerticalHandlePoint(200f, 10f), layout.selectionHandle(6, true))
        assertEquals(VerticalHandlePoint(200f, 10f), layout.selectionHandle(7, false))
    }

    @Test
    fun `T2_縦中横のつまみはマスの端で折り返し後も同じ`() {
        val layout = layout("甲12乙丙丁", 60f)
        assertEquals(VerticalHandlePoint(260f, 30f), layout.selectionHandle(1, true))
        assertEquals(VerticalHandlePoint(260f, 50f), layout.selectionHandle(3, false))
        assertEquals(VerticalHandlePoint(230f, 10f), layout.selectionHandle(3, true))
        assertEquals(VerticalHandlePoint(230f, 50f), layout.selectionHandle(5, false))
    }

    @Test
    fun `T3_先頭付近は右端で止まり中ほどと末尾は画面中央`() {
        val layout = layout(List(20) { "甲" }.joinToString("\n"))
        assertEquals(0f, layout.scrollToOffset(0, 0f, true), 0f)
        for (offset in listOf(16, 38)) {
            val target = layout.scrollToOffset(offset, 0f, true)
            assertEquals(150f, layout.cursorPosition(offset, target).centerX, 0f)
            assertTrue(target <= layout.scrollLimit(true))
        }
        assertTrue(layout.scrollLimit(true) > layout.maxScrollX)
        assertEquals(layout.maxScrollX, layout.scrollLimit(false), 0f)
        assertEquals(320f, layout.maxScrollX, 0f)
        assertEquals(layout.maxScrollX, layout.scrollToOffset(38, 1000f, false), 0f)
    }

    @Test
    fun `T3_短い本文でも最後の列が中央へ来られるが先頭より前には送らない`() {
        val layout = layout("甲\n乙\n丙\n丁\n戊\n己")
        assertEquals(0f, layout.maxScrollX, 0f)
        val target = layout.scrollToOffset(10, 0f, true)
        assertEquals(150f, layout.cursorPosition(10, target).centerX, 0f)
        assertEquals(0f, layout.scrollToOffset(0, target, true), 0f)
    }

    @Test
    fun `T4_一致とマスの重なりは一列と折り返しで同じ`() {
        val layout = layout("甲乙丙丁戊己庚辛", 100f)
        fun offsets(start: Int, end: Int) = layout.columns.flatMap { it.glyphs }.filter { it.overlaps(start, end) }.map { it.offset }
        assertEquals(listOf(1, 2), offsets(1, 3))
        assertEquals(listOf(3, 4), offsets(3, 5))
        assertEquals(emptyList<Int>(), offsets(3, 3))
        assertEquals(emptyList<Int>(), offsets(5, 3))
    }

    @Test
    fun `T4_段落の頭と末尾と縦中横にかかる一致`() {
        val layout = layout("甲12乙\r\n丙丁")
        fun offsets(start: Int, end: Int) = layout.columns.flatMap { it.glyphs }.filter { it.overlaps(start, end) }.map { it.offset }
        assertEquals(listOf(0), offsets(0, 1))
        assertEquals(listOf(1), offsets(2, 3))
        assertEquals(listOf(3, 6), offsets(3, 7))
        assertEquals(listOf(7), offsets(7, 8))
        assertEquals(emptyList<Int>(), offsets(4, 6))
    }

    @Test
    fun `選択メニューは右を優先し空きがなければ左そして上下で画面内`() {
        val layout = layout("甲\n乙\n丙\n丁\n戊\n己")
        assertEquals(VerticalHandlePoint(180f, 10f), layout.textActionPosition(10, 11, 0f, 100f, 48f, 40f))
        assertEquals(VerticalHandlePoint(120f, 30f), layout.textActionPosition(0, 1, 0f, 100f, 48f, 40f))
        val fallback = layout.textActionPosition(0, 11, 1000f, 280f, 48f, 40f)
        assertTrue(fallback.x in 0f..20f)
        assertTrue(fallback.y in 0f..252f)
    }
}
