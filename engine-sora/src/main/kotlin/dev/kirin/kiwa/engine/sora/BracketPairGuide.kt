package dev.kirin.kiwa.engine.sora

/**
 * 今の括弧の組を結ぶ線の形（VS Code の `editor.guides.bracketPairs: "active"` と同じ考え方）。
 *
 * **Android に依存しない計算だけ** ── 線を座標に直して描くのは [KiwaCodeEditor.onDraw]。
 * 桁は**表示の桁**（先頭のタブ1つを [tabWidth] 桁と数える）。字下げの種類（タブ / 空白）が
 * 行ごとに違っても、同じ桁なら同じ段として扱うため。
 *
 * ```
 * if (x) {          ← 上の横線: 開きの行の下端に、縦線の桁から `{` まで
 *     foo()         ← 縦線: 開きの次の行から閉じの行の上端まで、開きの行の字下げの桁
 * }                 ← 下の横線: 閉じが縦線の桁より右にある時だけ（ここでは無し）
 * ```
 */
internal object BracketPairGuide {

    sealed interface Segment

    /** 縦線。[fromLine] の上端から [toLine] の上端まで、[column] の位置に引く。 */
    data class Vertical(val column: Int, val fromLine: Int, val toLine: Int) : Segment

    /** 横線。[line] の上端（[atBottom] が true なら下端）に、[fromColumn] から [toColumn] まで引く。 */
    data class Horizontal(val line: Int, val atBottom: Boolean, val fromColumn: Int, val toColumn: Int) : Segment

    /**
     * 線の一覧。同じ行の組は線なし。
     *
     * @param indentColumn 開きの行の字下げの桁（[indentColumns]）
     */
    fun segments(
        openLine: Int,
        openColumn: Int,
        closeLine: Int,
        closeColumn: Int,
        indentColumn: Int
    ): List<Segment> {
        if (closeLine <= openLine) return emptyList()
        val out = ArrayList<Segment>(3)
        if (openColumn > indentColumn) {
            out.add(Horizontal(openLine, atBottom = true, fromColumn = indentColumn, toColumn = openColumn))
        }
        if (closeLine > openLine + 1) {
            out.add(Vertical(indentColumn, openLine + 1, closeLine))
        }
        if (closeColumn > indentColumn) {
            out.add(Horizontal(closeLine, atBottom = false, fromColumn = indentColumn, toColumn = closeColumn))
        }
        return out
    }

    /** 先頭の空白の桁数。タブは [tabWidth] 桁、空白は1桁。 */
    fun indentColumns(line: CharSequence, tabWidth: Int): Int {
        var columns = 0
        for (ch in line) {
            columns += when (ch) {
                ' ' -> 1
                '\t' -> tabWidth
                else -> return columns
            }
        }
        return columns
    }

    /** [charIndex] 番目の文字の表示の桁。タブは [tabWidth] 桁と数える。 */
    fun displayColumn(line: CharSequence, charIndex: Int, tabWidth: Int): Int {
        var columns = 0
        for (i in 0 until minOf(charIndex, line.length)) {
            columns += if (line[i] == '\t') tabWidth else 1
        }
        return columns
    }

    /** [displayColumn] の逆。桁がタブの途中に落ちたら、そのタブの次の文字。行より右なら行末。 */
    fun charIndexAt(line: CharSequence, displayColumn: Int, tabWidth: Int): Int {
        var columns = 0
        for (i in line.indices) {
            if (columns >= displayColumn) return i
            columns += if (line[i] == '\t') tabWidth else 1
        }
        return line.length
    }
}
