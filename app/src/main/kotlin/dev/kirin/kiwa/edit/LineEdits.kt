package dev.kirin.kiwa.edit

/**
 * 置き換える指示。**行の集合を丸ごと置き換える1手**として表す。
 * インデックスは全部「文字列の先頭からの文字数」。
 *
 * **Android を1つも知らない。** 引数も戻り値も文字列と整数だけなので、
 * 実機を出さずに全部試験できる（画面の中に埋めると踏めるのは実機だけになる）。
 */
class Edit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

/**
 * コメント / 字下げ / 行移動 / smart home の中身。**行の操作は全部
 * 「範囲を1つ置き換える」で書けるので、口は [Edit] 1つで足りる**
 * （エンジン側の約束は `EditorEngine.replaceRange` の Javadoc）。
 *
 * ## 行の切り出し方
 *
 * どの関数も、まず [splitLines] で行に割る。1行は「内容の始まり」「内容の終わり
 * （改行文字を含まない）」「次の行の始まり（＝この行の改行文字を含む終わり）」の3点で持つ。
 * **改行の文字そのもの（`\n` / `\r\n` / `\r`）は常に元の文字列から拾って使い、
 * 決め打ちしない** ── 行移動は改行をまたいで文字列を組み替えるので、
 * ここで `\n` に決め打つと CRLF のファイルが LF と混ざる。
 */
object LineEdits {

    // ------------------------------------------------------------------
    // コメントの切り替え
    // ------------------------------------------------------------------

    /**
     * 行コメントを付ける／外す。[lineComment] が null（行コメントを持たない言語）なら null。
     *
     * 付ける位置は各行の**最初の非空白の位置**（インデントの直後）。空行はそこが行末と同じになる。
     *
     * 選択が触れている行のうち、**内容のある行が1つでもコメントされていなければ全部に付ける**
     * （一部だけ付いている行は二重に付く ── VSCode と同じ動き）。**内容のある行が全部
     * コメントされていれば全部外す。** 内容の無い行（空行）はこの判定には数えないが、
     * 付ける／外すの操作自体は他の行と同じように受ける（外すときに揃うように、空行にも付ける）。
     */
    fun toggleComment(text: String, selStart: Int, selEnd: Int, lineComment: String?): Edit? {
        if (lineComment == null) return null

        val lines = splitLines(text)
        val startLineIdx = lineIndexAt(lines, selStart)
        val endLineIdx = lineIndexAt(lines, selEnd)
        val firstIdx = minOf(startLineIdx, endLineIdx)
        val lastIdx = maxOf(startLineIdx, endLineIdx)
        val count = lastIdx - firstIdx + 1

        val indentEnds = IntArray(count)
        val hasContent = BooleanArray(count)
        val commented = BooleanArray(count)
        for (i in firstIdx..lastIdx) {
            val line = lines[i]
            val end = indentEndOf(text, line)
            val idx = i - firstIdx
            indentEnds[idx] = end
            hasContent[idx] = end < line.contentEnd
            commented[idx] = hasContent[idx] && text.regionMatches(end, lineComment, 0, lineComment.length)
        }

        // 判定に数えるのは内容のある行だけ。空行しか選ばれていなければ、素直に「付ける」側へ倒す。
        val consider = (0 until count).filter { hasContent[it] }
        val removeMode = consider.isNotEmpty() && consider.all { commented[it] }

        val editStart = lines[firstIdx].start
        val editEnd = lines[lastIdx].contentEnd
        val sb = StringBuilder()
        val deltas = IntArray(count)

        for (i in firstIdx..lastIdx) {
            if (i > firstIdx) sb.append(text, lines[i - 1].contentEnd, lines[i].start)
            val line = lines[i]
            val idx = i - firstIdx
            val end = indentEnds[idx]
            val before = text.substring(line.start, end)
            if (removeMode) {
                if (commented[idx]) {
                    // 記号の後ろの空白1つまでは一緒に消す（`// x` も `//x` も外せる）。
                    var markerLen = lineComment.length
                    if (end + markerLen < line.contentEnd && text[end + markerLen] == ' ') markerLen++
                    sb.append(before)
                    sb.append(text, end + markerLen, line.contentEnd)
                    deltas[idx] = -markerLen
                } else {
                    // 空行など、そもそも付いていなかった行はそのまま。
                    sb.append(text, line.start, line.contentEnd)
                    deltas[idx] = 0
                }
            } else {
                val after = text.substring(end, line.contentEnd)
                sb.append(before).append(lineComment).append(' ').append(after)
                deltas[idx] = lineComment.length + 1
            }
        }

        fun shift(pos: Int, lineIdx: Int): Int {
            var cumulative = 0
            for (i in firstIdx until lineIdx) cumulative += deltas[i - firstIdx]
            val idx = lineIdx - firstIdx
            val p = indentEnds[idx]
            val own = if (removeMode) {
                if (commented[idx]) -(pos - p).coerceIn(0, -deltas[idx]) else 0
            } else {
                // **境界は挿入の前に残す**（`pos == p` は動かさない）── 選択の端が
                // ちょうど挿入位置にあるとき、その端は新しく差し込んだ側でなく元の側に留まる。
                // C5 のように行頭ぴったりを選んでいた場合、選択が挿入した記号ごと
                // 広がるのでなく、記号の手前で選択が始まったまま揃う。
                if (pos > p) deltas[idx] else 0
            }
            return cumulative + own
        }

        return Edit(
            editStart, editEnd, sb.toString(),
            selStart + shift(selStart, startLineIdx),
            selEnd + shift(selEnd, endLineIdx)
        )
    }

    // ------------------------------------------------------------------
    // 字下げ / 逆字下げ
    // ------------------------------------------------------------------

    /** 字下げする。[unit] を選択が触れている行の**先頭**（列0）へ、行ごとに1回差し込む。 */
    fun indent(text: String, selStart: Int, selEnd: Int, unit: String): Edit {
        val lines = splitLines(text)
        val startLineIdx = lineIndexAt(lines, selStart)
        val endLineIdx = lineIndexAt(lines, selEnd)
        val firstIdx = minOf(startLineIdx, endLineIdx)
        val lastIdx = maxOf(startLineIdx, endLineIdx)

        val editStart = lines[firstIdx].start
        val editEnd = lines[lastIdx].contentEnd
        val sb = StringBuilder()
        for (i in firstIdx..lastIdx) {
            if (i > firstIdx) sb.append(text, lines[i - 1].contentEnd, lines[i].start)
            sb.append(unit).append(text, lines[i].start, lines[i].contentEnd)
        }

        // 差し込み位置は各行の列0。**そこに乗っていた境界は差し込みの前に残る**
        // （選択の端が行頭ぴったりなら、差し込んだ字下げごと選択が広がる ── 揃えたまま伸びる）。
        // 行頭より後ろにあった境界だけ、その行より前の分＋自分の行の分だけ丸ごと後ろへ動く。
        fun shifted(pos: Int, lineIdx: Int): Int {
            val cumulative = unit.length * (lineIdx - firstIdx)
            val own = if (pos > lines[lineIdx].start) unit.length else 0
            return pos + cumulative + own
        }

        return Edit(editStart, editEnd, sb.toString(), shifted(selStart, startLineIdx), shifted(selEnd, endLineIdx))
    }

    /**
     * 字下げを戻す。**戻すものが1行も無ければ null**。
     *
     * 行の頭が [unit] とそのまま一致すればまるごと1段消す。一致しなくても、
     * 先頭の空白（スペース・タブ）を [unit] の文字数を上限に消す ── **1段に満たない空白も
     * 全部消す**。タブは幅に関係なく1文字で1段ぶんとして扱い、そこで止める。
     */
    fun outdent(text: String, selStart: Int, selEnd: Int, unit: String): Edit? {
        val lines = splitLines(text)
        val startLineIdx = lineIndexAt(lines, selStart)
        val endLineIdx = lineIndexAt(lines, selEnd)
        val firstIdx = minOf(startLineIdx, endLineIdx)
        val lastIdx = maxOf(startLineIdx, endLineIdx)
        val count = lastIdx - firstIdx + 1

        val removals = IntArray(count)
        for (i in firstIdx..lastIdx) {
            val line = lines[i]
            removals[i - firstIdx] = outdentAmount(text.substring(line.start, line.contentEnd), unit)
        }
        if (removals.all { it == 0 }) return null

        val editStart = lines[firstIdx].start
        val editEnd = lines[lastIdx].contentEnd
        val sb = StringBuilder()
        for (i in firstIdx..lastIdx) {
            if (i > firstIdx) sb.append(text, lines[i - 1].contentEnd, lines[i].start)
            val line = lines[i]
            val removed = removals[i - firstIdx]
            // **戻せる行だけ戻す** ── removed が 0 の行はそのまま素通し。
            sb.append(text, line.start + removed, line.contentEnd)
        }

        fun shifted(pos: Int, lineIdx: Int): Int {
            var cumulative = 0
            for (i in firstIdx until lineIdx) cumulative += removals[i - firstIdx]
            val offsetWithinLine = pos - lines[lineIdx].start
            val ownRemoval = minOf(offsetWithinLine, removals[lineIdx - firstIdx])
            return pos - cumulative - ownRemoval
        }

        return Edit(editStart, editEnd, sb.toString(), shifted(selStart, startLineIdx), shifted(selEnd, endLineIdx))
    }

    private fun outdentAmount(line: String, unit: String): Int {
        if (unit.isNotEmpty() && line.startsWith(unit)) return unit.length
        var i = 0
        while (i < line.length && i < unit.length) {
            val c = line[i]
            if (c == '\t') {
                // タブは幅に関係なく1段ぶん。そこで止める（続きの空白は次回の操作で見る）。
                i++
                break
            }
            if (c != ' ') break
            i++
        }
        return i
    }

    // ------------------------------------------------------------------
    // 行の上下移動
    // ------------------------------------------------------------------

    /**
     * 行を上へ。**先頭行を含む選択なら null**。
     *
     * **末尾の改行が作る空の行も動かさない**（[lastMovableIndex]）── そこを動かすと
     * ファイル末尾の改行が消える。
     */
    fun moveLineUp(text: String, selStart: Int, selEnd: Int): Edit? {
        val lines = splitLines(text)
        val startLineIdx = lineIndexAt(lines, selStart)
        val endLineIdx = lineIndexAt(lines, selEnd)
        val firstIdx = minOf(startLineIdx, endLineIdx)
        val lastIdx = maxOf(startLineIdx, endLineIdx)
        if (firstIdx == 0) return null
        if (firstIdx > lastMovableIndex(lines)) return null

        val above = lines[firstIdx - 1]
        val groupFirst = lines[firstIdx]
        val groupLast = lines[lastIdx]

        val editStart = above.start
        val editEnd = groupLast.end
        // 元は [above][区切り] [group][末尾の区切り]。区切りは above の後ろにあったものを
        // そのまま group の後ろへ持っていく ── **改行の文字も個数もここでは増えない**。
        val separator = text.substring(above.contentEnd, above.end)
        val aboveContent = text.substring(above.start, above.contentEnd)
        val groupBlock = text.substring(groupFirst.start, groupLast.contentEnd)
        val trailing = text.substring(groupLast.contentEnd, groupLast.end)
        val replacement = groupBlock + separator + aboveContent + trailing

        // group の中の相対位置はそのまま（カーソルだけの選択が行の中の同じ場所に留まる）。
        val newBlockStart = editStart
        val relStart = selStart - groupFirst.start
        val relEnd = selEnd - groupFirst.start
        return Edit(editStart, editEnd, replacement, newBlockStart + relStart, newBlockStart + relEnd)
    }

    /**
     * 行を下へ。**末尾行を含む選択なら null**。
     *
     * ここでいう末尾行は[lastMovableIndex]の行 ── **末尾の改行が作る空の行は数えない**。
     * 数えると、`"abc\ndef\n"` の `def` を下へ動かしたときに空行と入れ替わって
     * **`"abc\n\ndef"` になり、ファイル末尾の改行が消える**（実測で見つけた）。
     * 触っていない行のバイトを変えないのがこの repo の方針（`TextFile` の目標）。
     */
    fun moveLineDown(text: String, selStart: Int, selEnd: Int): Edit? {
        val lines = splitLines(text)
        val startLineIdx = lineIndexAt(lines, selStart)
        val endLineIdx = lineIndexAt(lines, selEnd)
        val firstIdx = minOf(startLineIdx, endLineIdx)
        val lastIdx = maxOf(startLineIdx, endLineIdx)
        if (lastIdx >= lastMovableIndex(lines)) return null

        val groupFirst = lines[firstIdx]
        val groupLast = lines[lastIdx]
        val below = lines[lastIdx + 1]

        val editStart = groupFirst.start
        val editEnd = below.end
        val separator = text.substring(groupLast.contentEnd, groupLast.end)
        val groupBlock = text.substring(groupFirst.start, groupLast.contentEnd)
        val belowContent = text.substring(below.start, below.contentEnd)
        val trailing = text.substring(below.contentEnd, below.end)
        val replacement = belowContent + separator + groupBlock + trailing

        val newBlockStart = editStart + belowContent.length + separator.length
        val relStart = selStart - groupFirst.start
        val relEnd = selEnd - groupFirst.start
        return Edit(editStart, editEnd, replacement, newBlockStart + relStart, newBlockStart + relEnd)
    }

    // ------------------------------------------------------------------
    // smart home
    // ------------------------------------------------------------------

    /**
     * smart home。字下げの直後（＝最初の非空白の位置）と行頭を往復する。
     *
     * 既に字下げの直後にいれば行頭へ、そうでなければ字下げの直後へ。
     * 字下げが無い行は両者が同じ位置なので、そこへ行くだけ（＝行頭）。
     * 空白だけの行・空行も同じ式で自然に扱える（字下げの直後 = 行末）。
     */
    fun smartHome(text: String, caret: Int): Int {
        val lines = splitLines(text)
        val line = lines[lineIndexAt(lines, caret)]
        val indentEnd = indentEndOf(text, line)
        return if (caret == indentEnd) line.start else indentEnd
    }

    // ------------------------------------------------------------------
    // 行の切り出し
    // ------------------------------------------------------------------

    /**
     * 1行分。[start] は内容の始まり、[contentEnd] は内容の終わり（改行文字を含まない）、
     * [end] は次の行の始まり（＝この行の改行文字を含めた終わり。最後の行に改行が無ければ
     * [contentEnd] と同じ）。
     */
    private class Line(val start: Int, val contentEnd: Int, val end: Int)

    /**
     * 改行 `\n` / `\r\n` / `\r` のどれでも割る。**末尾に改行があれば、その後ろに
     * 空の行が1本残る**（"abc\n" は "abc" と空行の2行 ── カーソルが改行の直後に
     * 置けることに対応する、ふつうのテキストエディタの数え方）。
     */
    private fun splitLines(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var start = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\n' -> {
                    lines.add(Line(start, i, i + 1))
                    i++
                    start = i
                }
                '\r' -> {
                    val end = if (i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                    lines.add(Line(start, i, end))
                    i = end
                    start = i
                }
                else -> i++
            }
        }
        lines.add(Line(start, text.length, text.length))
        return lines
    }

    /**
     * 動かしてよい最後の行。
     *
     * [splitLines] は末尾に改行があると**その後ろへ空の行を1本足す**（カーソルを
     * 置ける位置に対応する、ふつうのテキストエディタの数え方）。ただしその行には
     * 内容も改行も無い ── **動かすと改行が1つ消える**ので、移動の対象から外す。
     */
    private fun lastMovableIndex(lines: List<Line>): Int {
        val last = lines.size - 1
        val phantom = last > 0 && lines[last].start == lines[last].end
        return if (phantom) last - 1 else last
    }

    /** [pos] が乗っている行の番号。行の境目に立っていれば、後ろの行に数える。 */
    private fun lineIndexAt(lines: List<Line>, pos: Int): Int {
        for (i in lines.indices) {
            val nextStart = if (i + 1 < lines.size) lines[i + 1].start else Int.MAX_VALUE
            if (pos < nextStart) return i
        }
        return lines.size - 1
    }

    /** その行の最初の非空白の位置。無ければ内容の終わり（＝空行や空白だけの行はそこ）。 */
    private fun indentEndOf(text: String, line: Line): Int {
        var end = line.start
        while (end < line.contentEnd && (text[end] == ' ' || text[end] == '\t')) end++
        return end
    }
}
