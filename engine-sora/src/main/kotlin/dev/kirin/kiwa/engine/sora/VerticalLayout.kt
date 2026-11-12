package dev.kirin.kiwa.engine.sora

import java.text.BreakIterator

/**
 * 縦書きレイアウト・幾何計算。
 *
 * ## 由来とライセンス
 *
 * 入力部品（Soraの文書/IC/Undo）を残し、描画と指の座標変換・キー読み替えを独立して
 * 接続する構成、および座標と文字インデックスの相互変換の考え方は、
 * MMTextKit（MIT、commit fa0bf1165dcf452eba3bce2a7951441ff3048b0d）を参考に翻案した。
 * MMTextKit の元コードは Compose Multiplatform 用（VTextField.kt / VerticalText.kt）であり、
 * 本実装は Android View / Canvas 用に再構築し、日本語の右から左（RTL）列配置、
 * 約物・長音符の縦用配置、サロゲート境界の保護を追加している。
 *
 * ---
 * MMTextKit LICENSE:
 * MIT License
 * Copyright (c) 2026 lzdev42
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * ---
 */

/** UTR #50 の区分。端末の ICU が返す値を描画・幾何から独立させる。 */
internal enum class VerticalOrientation { U, R, TU, TR }

/** 縦書き内の1マス（書記素クラスタまたは縦中横）の幾何情報 */
internal data class VerticalGlyph(
    val offset: Int,
    val length: Int,
    val text: String,
    val isUpright: Boolean,
    val isTateChuYoko: Boolean,
    val isWhitespace: Boolean,
    val topY: Float,
    val bottomY: Float,
    val centerX: Float
) {
    val endOffset: Int get() = offset + length
    val centerY: Float get() = (topY + bottomY) / 2f
    fun overlaps(start: Int, end: Int): Boolean = start < end && offset < end && endOffset > start
}

/** 縦書きの1列（Column） */
internal data class VerticalColumn(
    val columnIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val rightX: Float,
    val columnWidth: Float,
    val glyphs: List<VerticalGlyph>
) {
    val leftX: Float get() = rightX - columnWidth
    val centerX: Float get() = rightX - columnWidth / 2f
}

/** カーソル位置の情報 */
internal data class VerticalCursorPoint(
    val x1: Float,
    val x2: Float,
    val y: Float,
    val centerX: Float,
    val lineTop: Float,
    val lineBottom: Float
)

/** 列の左辺に付く、選択つまみの接点。 */
internal data class VerticalHandlePoint(val x: Float, val y: Float)

/** 矢印キーの向き */
internal enum class ArrowDirection {
    UP, DOWN, LEFT, RIGHT
}

/** Sora の1行に対応する、改行を含まない段落と後続の改行の長さ。 */
internal data class VerticalParagraph(val text: String, val separatorLength: Int) {
    init {
        require(separatorLength in 0..2)
        require('\r' !in text && '\n' !in text)
    }
}

/**
 * 段落内は相対位置で保持し、通し位置は prefix の二分探索で引く。
 * 改行なしの長大な段落は編集ごとに全体を組み直す。必要になれば段落内を分割する。
 */
internal class VerticalLayout private constructor(
    val lineHeight: Float,
    val columnWidth: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val paddingTop: Float,
    val paddingBottom: Float,
    val paddingLeft: Float,
    val paddingRight: Float,
    private val orientation: (Int) -> VerticalOrientation,
    private val hasVerticalGlyph: (String) -> Boolean,
    private val lineBreaks: (String) -> Set<Int>,
    private val sidewaysAdvance: (String) -> Float
) {
    private data class ParagraphLayout(val input: VerticalParagraph, val columns: List<VerticalColumn>)
    private val paragraphs = ArrayList<ParagraphLayout>()
    private val characterBreaks = BreakIterator.getCharacterInstance()
    private var offsetStarts = intArrayOf(0)
    private var columnStarts = intArrayOf(0)

    val paragraphCount: Int get() = paragraphs.size
    val textLength: Int get() = offsetStarts.last()
    val columnCount: Int get() = columnStarts.last()
    val totalWidth: Float get() = paddingRight + columnCount * columnWidth + paddingLeft
    val maxScrollX: Float get() = (totalWidth - viewportWidth).coerceAtLeast(0f)

    // 全列を複製しない。描画側は columnIndexRangeInX で画面内の列だけを取り出す。
    val columns: List<VerticalColumn> = object : AbstractList<VerticalColumn>() {
        override val size: Int get() = columnCount
        override fun get(index: Int): VerticalColumn {
            require(index in indices)
            val p = prefixIndex(columnStarts, index)
            val local = paragraphs[p].columns[index - columnStarts[p]]
            val offset = offsetStarts[p]
            val rightX = columnRightX(index)
            return local.copy(
                columnIndex = index,
                startOffset = local.startOffset + offset,
                endOffset = local.endOffset + offset,
                rightX = rightX,
                glyphs = local.glyphs.map { it.copy(offset = it.offset + offset, centerX = rightX - columnWidth / 2f) }
            )
        }
    }

    /** タイプライター中だけ、最後の列の中央が画面中央へ来るまで送れる。 */
    fun scrollLimit(typewriter: Boolean): Float = if (typewriter) {
        maxOf(maxScrollX, viewportWidth / 2f - (columnRightX(columnCount - 1) - columnWidth / 2f))
    } else maxScrollX

    /** 通常は最小限、タイプライター中は列の中央を画面中央へ。本文の頭より前には送らない。 */
    fun scrollToOffset(offset: Int, currentScroll: Float, typewriter: Boolean): Float {
        val center = cursorPosition(offset).centerX
        val target = if (typewriter) viewportWidth / 2f - center else when {
            center + currentScroll < columnWidth -> columnWidth - center
            center + currentScroll > viewportWidth - columnWidth -> viewportWidth - columnWidth - center
            else -> currentScroll
        }
        return target.coerceIn(0f, scrollLimit(typewriter))
    }

    /** 末尾は offset の次の列ではなく、最後に選ばれたマスの下辺に付ける。改行だけなら空列の上辺。 */
    fun selectionHandle(offset: Int, start: Boolean, scrollX: Float = 0f): VerticalHandlePoint {
        val safe = offset.coerceIn(0, textLength)
        val column = columns[columnIndexOf(if (start) safe else (safe - 1).coerceAtLeast(0))]
        val glyph = if (start) column.glyphs.firstOrNull { it.endOffset > safe }
            else column.glyphs.lastOrNull { it.offset < safe }
        val y = if (start) glyph?.topY ?: column.glyphs.lastOrNull()?.bottomY ?: paddingTop
            else glyph?.bottomY ?: paddingTop
        return VerticalHandlePoint(column.leftX + scrollX, y)
    }

    /** 選択の先頭列の右 → 末尾列の左 → 上下。置けない場合は画面内に収める。 */
    fun textActionPosition(left: Int, right: Int, scrollX: Float, width: Float, height: Float, gap: Float): VerticalHandlePoint {
        val first = selectionHandle(left, true, scrollX)
        val last = selectionHandle(right, false, scrollX)
        val maxX = (viewportWidth - width).coerceAtLeast(0f)
        val maxY = (viewportHeight - height).coerceAtLeast(0f)
        val besideY = first.y.coerceIn(0f, maxY)
        val afterFirst = first.x + columnWidth + gap
        val beforeLast = last.x - gap - width
        if (afterFirst in 0f..maxX) return VerticalHandlePoint(afterFirst, besideY)
        if (beforeLast in 0f..maxX) return VerticalHandlePoint(beforeLast, last.y.coerceIn(0f, maxY))
        val x = ((first.x + last.x + columnWidth - width) / 2f).coerceIn(0f, maxX)
        val top = minOf(first.y, last.y) - gap - height
        val bottom = maxOf(first.y, last.y) + gap
        val y = when {
            top in 0f..maxY -> top
            bottom in 0f..maxY -> bottom
            else -> besideY
        }
        return VerticalHandlePoint(x, y)
    }

    /** 指定した段落だけ再組版する。未変更の段落には4つの関数を呼ばない。 */
    fun replaceParagraphs(first: Int, removeCount: Int, replacement: List<VerticalParagraph>) {
        require(first >= 0 && removeCount >= 0 && first + removeCount <= paragraphs.size)
        require(paragraphs.size - removeCount + replacement.size > 0)
        val built = replacement.map { buildParagraph(it) }
        paragraphs.subList(first, first + removeCount).clear()
        paragraphs.addAll(first, built)
        // 上限10万字では段落数ぶん足し直す。必要になれば prefix を部分和の木に替える。
        offsetStarts = IntArray(paragraphs.size + 1)
        columnStarts = IntArray(paragraphs.size + 1)
        for (i in paragraphs.indices) {
            val p = paragraphs[i]
            offsetStarts[i + 1] = offsetStarts[i] + p.input.text.length + p.input.separatorLength
            columnStarts[i + 1] = columnStarts[i] + p.columns.size
        }
    }

    /** レイアウト座標の閉区間に少しでもかかる列（左右の端を含む）を、列の通し番号で返す。 */
    fun columnIndexRangeInX(minX: Float, maxX: Float): IntRange {
        if (minX > maxX) return IntRange.EMPTY
        val right = viewportWidth - paddingRight
        var first = (kotlin.math.ceil(((right - columnWidth - maxX) / columnWidth).toDouble())
            .toInt().coerceIn(0, columnCount) - 1).coerceAtLeast(0)
        var last = (kotlin.math.floor(((right - minX) / columnWidth).toDouble())
            .toInt().coerceIn(-1, columnCount - 1) + 1).coerceAtMost(columnCount - 1)
        // 小数の列幅と遠い列の Float 丸めを、前後1列と実際の辺で補正する（全文は走査しない）。
        while (first <= last && columnRightX(first) - columnWidth > maxX) first++
        while (last >= first && columnRightX(last) < minX) last--
        return if (first > last) IntRange.EMPTY else first..last
    }

    /** 列の右端（スクロールを足す前のレイアウト座標）。 */
    fun columnRightX(index: Int): Float = viewportWidth - paddingRight - index * columnWidth

    /**
     * 列の描画を覚えておくための鍵。**同一性（===）で比べる**。列のある段落が組み直されるまで同じ物を返し、
     * 段落の位置がずれただけ（前の段落が伸び縮みした）では替わらない。
     */
    fun columnKey(index: Int): Any {
        require(index in 0 until columnCount)
        val p = prefixIndex(columnStarts, index)
        return paragraphs[p].columns[index - columnStarts[p]]
    }

    /** prefix の末尾（合計）を除いて、value 以下の最後の開始位置を引く。 */
    private fun prefixIndex(prefix: IntArray, value: Int): Int {
        var low = 0
        var high = prefix.size - 1
        while (low + 1 < high) {
            val mid = (low + high) ushr 1
            if (prefix[mid] <= value) low = mid else high = mid
        }
        return low
    }

    /** offset のカーソルが出る列の通し番号。 */
    fun columnIndexOf(offset: Int): Int {
        val p = prefixIndex(offsetStarts, offset.coerceIn(0, textLength))
        val localOffset = offset.coerceIn(0, textLength) - offsetStarts[p]
        val cols = paragraphs[p].columns
        var low = 0
        var high = cols.size
        while (low + 1 < high) {
            val mid = (low + high) ushr 1
            if (cols[mid].startOffset <= localOffset) low = mid else high = mid
        }
        return columnStarts[p] + low
    }

    fun offsetAt(screenX: Float, screenY: Float, scrollX: Float = 0f): Int {
        val firstCenter = viewportWidth - paddingRight - columnWidth / 2f
        // 中間ちょうどなら右側の列（元の総当たりと同じ）。
        val index = kotlin.math.ceil(((firstCenter - screenX + scrollX) / columnWidth - 0.5f).toDouble())
            .toInt().coerceIn(0, columnCount - 1)
        val col = columns[index]
        if (col.glyphs.isEmpty()) return col.startOffset
        if (screenY <= col.glyphs.first().topY) return col.startOffset
        if (screenY >= col.glyphs.last().bottomY) return col.glyphs.last().endOffset
        for (glyph in col.glyphs) {
            if (screenY in glyph.topY..glyph.bottomY) {
                return if (screenY <= glyph.centerY) glyph.offset else glyph.endOffset
            }
        }
        return col.startOffset
    }

    fun cursorPosition(offset: Int, scrollX: Float = 0f): VerticalCursorPoint {
        val safeOffset = offset.coerceIn(0, textLength)
        val p = prefixIndex(offsetStarts, safeOffset)
        // 改行の途中（CRLF）なら、その手前へ。
        val normalized = offsetStarts[p] + (safeOffset - offsetStarts[p]).coerceAtMost(paragraphs[p].input.text.length)
        val col = columns[columnIndexOf(normalized)]
        val cx = col.centerX + scrollX
        val barWidth = (columnWidth * 0.4f).coerceAtLeast(8f)
        if (col.glyphs.isEmpty()) {
            return VerticalCursorPoint(cx - barWidth, cx + barWidth, paddingTop, cx, paddingTop, paddingTop + lineHeight)
        }
        for (g in col.glyphs) {
            if (normalized in g.offset until g.endOffset) {
                val y = if (g.isTateChuYoko && normalized > g.offset) g.centerY else g.topY
                return VerticalCursorPoint(cx - barWidth, cx + barWidth, y, cx, g.topY, g.bottomY)
            }
        }
        val last = col.glyphs.last()
        return VerticalCursorPoint(cx - barWidth, cx + barWidth, last.bottomY, cx, last.topY, last.bottomY)
    }

    fun nextOffset(currentOffset: Int, direction: ArrowDirection): Int {
        val offset = currentOffset.coerceIn(0, textLength)
        val p = prefixIndex(offsetStarts, offset)
        val local = offset - offsetStarts[p]
        val input = paragraphs[p].input
        return when (direction) {
            ArrowDirection.UP -> when {
                offset == 0 -> 0
                local == 0 -> offsetStarts[p - 1] + paragraphs[p - 1].input.text.length
                local > input.text.length -> offsetStarts[p] + input.text.length
                else -> columns[columnIndexOf(offset - 1)].glyphs.last { it.offset < offset }.offset
            }
            ArrowDirection.DOWN -> when {
                offset == textLength -> textLength
                local >= input.text.length -> offsetStarts[p + 1]
                else -> columns[columnIndexOf(offset)].glyphs.first { it.endOffset > offset }.endOffset
            }
            ArrowDirection.LEFT, ArrowDirection.RIGHT -> {
                val target = columnIndexOf(offset) + if (direction == ArrowDirection.LEFT) 1 else -1
                if (target !in columns.indices) offset else nearestOffsetInColumn(target, cursorPosition(offset).y)
            }
        }
    }

    private fun nearestOffsetInColumn(index: Int, targetY: Float): Int {
        val col = columns[index]
        val last = col.glyphs.lastOrNull() ?: return col.startOffset
        var bestOffset = col.startOffset
        var minDist = Float.MAX_VALUE
        for (g in col.glyphs) {
            val dist = kotlin.math.abs(g.topY - targetY)
            if (dist < minDist) {
                minDist = dist
                bestOffset = g.offset
            }
        }
        val endsHere = last.endOffset < col.endOffset || index == columns.lastIndex
        if (endsHere && kotlin.math.abs(last.bottomY - targetY) < minDist) bestOffset = last.endOffset
        return bestOffset
    }

    private fun buildParagraph(input: VerticalParagraph): ParagraphLayout {
        val usableHeight = (viewportHeight - paddingTop - paddingBottom).coerceAtLeast(lineHeight)
        val columns = ArrayList<VerticalColumn>()
        val paragraph = input.text
        val breaks = lineBreaks(paragraph)
        characterBreaks.setText(paragraph)
        val cells = ArrayList<VerticalGlyph>()
        var start = characterBreaks.first()
        while (start < paragraph.length) {
            var end = characterBreaks.following(start)
            var tcy = false
            if (paragraph[start] in '0'..'9' &&
                (start == 0 || paragraph[start - 1] !in '0'..'9')
            ) {
                var digitEnd = start + 1
                while (digitEnd < paragraph.length && paragraph[digitEnd] in '0'..'9') digitEnd++
                val before = paragraph.getOrNull(start - 1)
                val after = paragraph.getOrNull(digitEnd)
                tcy = digitEnd - start <= 2 &&
                    (before == null || before !in '!'..'~') &&
                    (after == null || after !in '!'..'~') &&
                    characterBreaks.isBoundary(digitEnd)
                if (tcy) end = digitEnd
            }
            val cluster = paragraph.substring(start, end)
            val whitespace = cluster == " " || cluster == "　" || cluster == "\t"
            val upright = when (orientation(cluster.codePointAt(0))) {
                VerticalOrientation.U, VerticalOrientation.TU -> true
                VerticalOrientation.TR -> hasVerticalGlyph(cluster)
                VerticalOrientation.R -> false
            }
            val advance = when {
                cluster == " " -> lineHeight * 0.5f
                cluster == "　" -> lineHeight
                cluster == "\t" -> lineHeight * 2f
                tcy || upright -> lineHeight
                else -> sidewaysAdvance(cluster).let { if (it == 0f) lineHeight * 0.25f else it }
            }
            cells.add(VerticalGlyph(
                start, end - start, cluster, upright, tcy, whitespace,
                0f, advance, 0f
            ))
            start = end
        }

        var first = 0
        do {
            var limit = first
            var height = 0f
            while (limit < cells.size) {
                val advance = cells[limit].bottomY
                if (limit > first && height + advance > usableHeight) break
                height += advance
                limit++
            }
            if (limit < cells.size) {
                // 後方探索は列内だけ。長い不可分語でも必ず1マス以上進める。
                val legal = (limit downTo first + 1).firstOrNull {
                    cells[it].offset in breaks
                }
                if (legal != null) limit = legal
            }
            val index = columns.size
            val rightX = columnRightX(index)
            var y = paddingTop
            val glyphs = cells.subList(first, limit).map { cell ->
                val advance = cell.bottomY
                cell.copy(topY = y, bottomY = y + advance, centerX = rightX - columnWidth / 2f)
                    .also { y += advance }
            }
            columns.add(VerticalColumn(
                index,
                cells.getOrNull(first)?.offset ?: 0,
                if (limit == cells.size) input.text.length + input.separatorLength else cells[limit].offset,
                rightX, columnWidth, glyphs
            ))
            first = limit
        } while (first < cells.size)

        return ParagraphLayout(input, columns)
    }

    companion object {
        /** 全段落から作る口。本文の読み取りと改行の判定は呼び出し側が行う。 */
        fun build(
            paragraphs: List<VerticalParagraph>,
            lineHeight: Float,
            columnWidth: Float,
            viewportWidth: Float,
            viewportHeight: Float,
            paddingTop: Float = 16f,
            paddingBottom: Float = 16f,
            paddingLeft: Float = 16f,
            paddingRight: Float = 16f,
            orientation: (Int) -> VerticalOrientation,
            hasVerticalGlyph: (String) -> Boolean,
            lineBreaks: (String) -> Set<Int>,
            sidewaysAdvance: (String) -> Float
        ): VerticalLayout {
            require(lineHeight > 0f && columnWidth > 0f)
            val layout = VerticalLayout(
                lineHeight, columnWidth, viewportWidth, viewportHeight,
                paddingTop, paddingBottom, paddingLeft, paddingRight,
                orientation, hasVerticalGlyph, lineBreaks, sidewaysAdvance
            )
            layout.replaceParagraphs(0, 0, paragraphs)
            return layout
        }

    }
}
