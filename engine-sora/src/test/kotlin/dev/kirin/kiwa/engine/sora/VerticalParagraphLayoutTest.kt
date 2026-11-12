package dev.kirin.kiwa.engine.sora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun verticalParagraphsOf(text: CharSequence): List<VerticalParagraph> {
    val str = text.toString()
    val result = ArrayList<VerticalParagraph>()
    var start = 0
    do {
        val newline = str.indexOfAny(charArrayOf('\r', '\n'), start)
        val end = if (newline < 0) str.length else newline
        val separator = when {
            newline < 0 -> 0
            str[newline] == '\r' && str.getOrNull(newline + 1) == '\n' -> 2
            else -> 1
        }
        result.add(VerticalParagraph(str.substring(start, end), separator))
        start = end + separator
    } while (newline >= 0)
    return result
}

/** 範囲にかかる列そのものを読む（本体は通し番号だけを返す）。 */
internal fun VerticalLayout.columnsInXRange(minX: Float, maxX: Float): List<VerticalColumn> =
    columnIndexRangeInX(minX, maxX).map { columns[it] }

class VerticalParagraphLayoutTest {
    private fun build(text: CharSequence) = VerticalLayout.build(
        verticalParagraphsOf(text), 20f, 30f, 300f, 100f, 10f, 10f, 10f, 10f,
        orientation = { if (it < 128) VerticalOrientation.R else VerticalOrientation.U },
        hasVerticalGlyph = { false },
        lineBreaks = { (0..it.length).toSet() },
        sidewaysAdvance = { 11f }
    )

    private fun assertSameGeometry(expected: VerticalLayout, actual: VerticalLayout) {
        assertEquals(expected.textLength, actual.textLength)
        assertEquals(expected.paragraphCount, actual.paragraphCount)
        assertEquals(expected.columnCount, actual.columnCount)
        assertEquals(expected.totalWidth, actual.totalWidth, 0f)
        assertEquals(expected.maxScrollX, actual.maxScrollX, 0f)
        assertEquals(expected.columns, actual.columns)
        for (offset in 0..expected.textLength) {
            assertEquals("cursor $offset", expected.cursorPosition(offset), actual.cursorPosition(offset))
            for (direction in ArrowDirection.values()) {
                assertEquals("$direction $offset", expected.nextOffset(offset, direction), actual.nextOffset(offset, direction))
            }
        }
        for (col in expected.columns) {
            for (y in listOf(-1f, 10f, 20f, 30f, 60f, 90f, 110f)) {
                for (scroll in listOf(0f, expected.maxScrollX)) {
                    assertEquals(expected.offsetAt(col.centerX + scroll, y, scroll), actual.offsetAt(col.centerX + scroll, y, scroll))
                }
            }
        }
    }

    @Test
    fun `T1_挿入削除分割結合と複数行とCRLFの編集を順に差し替えて全文組版と一致`() {
        var document = "春「空」\n風12\r\n末尾\n"
        val layout = build(document)
        fun edit(start: Int, end: Int, inserted: String) {
            val before = verticalParagraphsOf(document)
            fun paragraphAt(offset: Int): Int {
                var first = 0
                for (i in before.indices) {
                    val next = first + before[i].text.length + before[i].separatorLength
                    if (offset < next || i == before.lastIndex) return i
                    first = next
                }
                error("unreachable")
            }
            val first = paragraphAt(start)
            val last = paragraphAt(end)
            val removed = last - first + 1
            document = document.replaceRange(start, end, inserted)
            val after = verticalParagraphsOf(document)
            val added = after.size - before.size + removed
            layout.replaceParagraphs(first, removed, after.subList(first, first + added))
            assertSameGeometry(build(document), layout)
        }
        edit(1, 1, "字") // 1字の挿入
        edit(1, 2, "") // 1字の削除
        edit(1, 1, "\n") // 段落の分割
        edit(1, 2, "") // 段落の結合
        edit(2, 2, "海a\n山「\r\n川\n") // 複数行の貼り付け
        edit(document.indexOf('a') + 1, document.indexOf('川') + 1, "") // 複数行の削除
        val tail = document.indexOf("末尾")
        edit(tail, tail, "甲\r\n乙\r\n")
        val crlf = document.indexOf("\r\n")
        edit(crlf, crlf + 2, "") // CRLF全体を1つの改行として削除
        edit(document.length, document.length, "\r\n\n")
        edit(0, document.length, "") // 全削除後も空列を保つ
    }

    @Test
    fun `列の鍵は組み直した段落だけ替わり_位置がずれただけの段落では替わらない`() {
        val layout = build("あいうえおかきく\nさしす\nたちつ")       // 列: 4+4 / 3 / 3 字（1列4字）
        assertEquals(4, layout.columnCount)
        val before = (0 until layout.columnCount).map { layout.columnKey(it) }
        assertTrue("別の列は別の鍵", before.toSet().size == 4 && before[0] !== before[1])
        assertTrue("読み直しても同じ鍵", layout.columnKey(2) === before[2])
        // 先頭の段落を3列へ伸ばす。後ろの段落は通し番号が1つずれるが、鍵は同じ。
        layout.replaceParagraphs(0, 1, verticalParagraphsOf("あいうえおかきくけこ\n").take(1))
        assertEquals(5, layout.columnCount)
        assertTrue("組み直した段落の列は新しい鍵", (0..2).none { i -> before.any { it === layout.columnKey(i) } })
        assertTrue(layout.columnKey(3) === before[2])
        assertTrue(layout.columnKey(4) === before[3])
        assertEquals(listOf(0, 1, 2, 3, 4), layout.columnIndexRangeInX(0f, 300f).toList())
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0, 4, 8, 11, 15).map { layout.columnIndexOf(it) })
    }

    @Test
    fun `T2_多段落の1段落の差し替えは4つの関数をその段落にしか呼ばない`() {
        val calls = mutableMapOf<String, MutableList<String>>()
        var active = ""
        fun record(kind: String) { calls.getOrPut(kind) { mutableListOf() }.add(active) }
        val inputs = List(2000) { VerticalParagraph("元「a", if (it == 1999) 0 else 1) }
        val layout = VerticalLayout.build(
            paragraphs = inputs,
            lineHeight = 20f, columnWidth = 30f, viewportWidth = 300f, viewportHeight = 100f,
            orientation = { record("orientation"); if (it == '「'.code) VerticalOrientation.TR else VerticalOrientation.R },
            hasVerticalGlyph = { record("glyph"); false },
            lineBreaks = { active = it; record("breaks"); (0..it.length).toSet() },
            sidewaysAdvance = { record("advance"); 12f }
        )
        calls.clear()
        layout.replaceParagraphs(1000, 1, listOf(VerticalParagraph("替「b", 1)))
        assertEquals(setOf("orientation", "glyph", "breaks", "advance"), calls.keys)
        assertEquals(listOf("替「b"), calls["breaks"])
        assertEquals(listOf("替「b"), calls["glyph"])
        assertEquals(List(3) { "替「b" }, calls["orientation"])
        assertEquals(List(3) { "替「b" }, calls["advance"])
        calls.clear()
        for (offset in listOf(0, layout.textLength / 2, layout.textLength)) {
            layout.cursorPosition(offset)
            for (direction in ArrowDirection.values()) layout.nextOffset(offset, direction)
        }
        layout.offsetAt(100f, 20f)
        layout.columnsInXRange(0f, 300f).toList()
        assertTrue("読むだけなら再組版しない", calls.isEmpty())
    }

    @Test
    fun `T3_10万字の頭中ほど末尾の位置とタップと4方向が一致`() {
        // 1行10文字（本文9+LF）を1万行。末尾改行の空段落も残す。
        val layout = build("あいうえおかきくけ\n".repeat(10000))
        assertEquals(100000, layout.textLength)
        assertEquals(30001, layout.columnCount) // 4文字ずつで3列
        for (line in listOf(0, 5000, 9999)) {
            val start = line * 10
            val col = layout.columns[line * 3]
            val scroll = -col.centerX + 100f
            assertEquals(start, layout.offsetAt(100f, 11f, scroll))
            assertEquals(10f, layout.cursorPosition(start, scroll).y, 0f)
            assertEquals(100f, layout.cursorPosition(start, scroll).centerX, 0.01f)
            assertEquals(start + 1, layout.nextOffset(start, ArrowDirection.DOWN))
            assertEquals(if (line == 0) 0 else start - 1, layout.nextOffset(start, ArrowDirection.UP))
            assertEquals(start + 4, layout.nextOffset(start, ArrowDirection.LEFT))
            assertEquals(if (line == 0) 0 else start - 2, layout.nextOffset(start, ArrowDirection.RIGHT))
            assertEquals(start + 9, layout.nextOffset(start + 8, ArrowDirection.DOWN))
            assertEquals(start + 10, layout.nextOffset(start + 9, ArrowDirection.DOWN))
        }
        val end = layout.cursorPosition(100000, layout.maxScrollX)
        assertTrue(end.centerX in 0f..300f)
        assertEquals(100000, layout.offsetAt(end.centerX, end.y, layout.maxScrollX))
        assertEquals(100000, layout.nextOffset(100000, ArrowDirection.DOWN))
        assertEquals(99999, layout.nextOffset(100000, ArrowDirection.UP))
        assertEquals(100000, layout.nextOffset(100000, ArrowDirection.LEFT))
        assertEquals(99998, layout.nextOffset(100000, ArrowDirection.RIGHT))
    }

    @Test
    fun `T3_LFとCRLFと空段落の境目を上下が1つとして越える`() {
        val layout = build("か\u3099\n12\r\n\r\n𠮷\r終")
        val stops = listOf(0, 2, 3, 5, 7, 9, 11, 12, 13)
        for ((a, b) in stops.zipWithNext()) {
            assertEquals("DOWN $a", b, layout.nextOffset(a, ArrowDirection.DOWN))
            assertEquals("UP $b", a, layout.nextOffset(b, ArrowDirection.UP))
        }
        assertEquals(layout.cursorPosition(5), layout.cursorPosition(6))
        assertEquals(5, layout.nextOffset(6, ArrowDirection.UP))
        assertEquals(7, layout.nextOffset(6, ArrowDirection.DOWN))
        assertEquals(layout.cursorPosition(7), layout.cursorPosition(8))
        assertEquals(9, layout.nextOffset(8, ArrowDirection.DOWN))
    }

    @Test
    fun `T4_xの閉区間にかかる列だけを端の列も含めて返す`() {
        val layout = build("あいうえおかきくけ\n".repeat(20))
        val middle = layout.columns[20]
        val left = layout.columns[23]
        val ranges = listOf(
            middle.centerX to middle.centerX,
            left.leftX to middle.rightX,
            middle.rightX to middle.rightX,
            middle.leftX to middle.leftX,
            301f to 400f,
            -10000f to -9999f,
            -10000f to 10000f,
            0f to 300f
        )
        for ((min, max) in ranges) {
            val expected = layout.columns.filter { it.rightX >= min && it.leftX <= max }
            assertEquals("$min..$max", expected, layout.columnsInXRange(min, max))
        }
        assertEquals(listOf(19, 20), layout.columnsInXRange(middle.rightX, middle.rightX).map { it.columnIndex })
        assertTrue(layout.columnsInXRange(1f, 0f).isEmpty())
    }

    @Test
    fun `T4_小数の列幅でも遠い列の境界を含む`() {
        val layout = VerticalLayout.build(
            paragraphs = verticalParagraphsOf("あ\n".repeat(40000)),
            lineHeight = 20.3f, columnWidth = 31.7f, viewportWidth = 300.4f, viewportHeight = 100.8f,
            orientation = { VerticalOrientation.U }, hasVerticalGlyph = { false },
            lineBreaks = { (0..it.length).toSet() }, sidewaysAdvance = { 20.3f }
        )
        for (index in listOf(0, 1, 1100, 19999, 39999, 40000)) {
            val col = layout.columns[index]
            for (x in listOf(col.leftX, col.rightX, col.centerX)) {
                val expected = layout.columns.filter { it.rightX >= x && it.leftX <= x }
                assertEquals("column $index x=$x", expected, layout.columnsInXRange(x, x))
            }
        }
    }
}
