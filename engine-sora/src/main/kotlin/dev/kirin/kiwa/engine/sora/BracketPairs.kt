package dev.kirin.kiwa.engine.sora

/**
 * 空の括弧の組を Backspace で一緒に消すかどうか、閉じ括弧を打った行をどこまで戻すかの判定。
 * **Sora を呼ばない**ので単体で試験できる。
 *
 * ## なぜ要るか
 *
 * Sora 0.24.6 は `(` を打つと `()` を入れて内側にカーソルを置くが、
 * その直後の Backspace は `(` だけを消して `)` を残す（`widget/CodeEditor.java:1947` の
 * `deleteText()` に組を見る処理が無い）。
 * そのため、空の括弧は Backspace で「**一緒に消す**」。
 *
 * ## 何を組として扱うか
 *
 * **`()` `[]` `{}` の3つだけ**、しかも**その言語が自動で閉じる組に持っているときだけ**。
 *
 * - 言語が閉じない種類（`.c` や `.txt` など）では、打った時に1文字ずつ入るので、
 *   消す時も1文字ずつにする ── 入れ方と消し方を揃える
 * - 引用符（`"` `'` `` ` ``）は入れていない。言語の表では「自動で閉じる組」と
 *   「選択を囲むだけの組」を公開された口から見分けられず（markdown は `*` や `_` を囲むだけ）、
 *   `"` は文字列の中で閉じない条件も付いている。そのため、対象を括弧に絞った
 */
internal object BracketPairs {

    private val BRACKETS = mapOf('(' to ')', '[' to ']', '{' to '}')

    /**
     * カーソルが空の組の間（`(|)`）にあって、組ごと消してよいか。
     *
     * @param line   カーソルのある行の文字
     * @param column カーソルの桁（0 始まり）
     * @param languageCloses 開き括弧に対して言語が入れる閉じ括弧。持っていなければ空
     */
    fun deletesBoth(line: CharSequence, column: Int, languageCloses: (Char) -> Collection<String>): Boolean {
        if (column <= 0 || column >= line.length) return false
        val open = line[column - 1]
        val close = BRACKETS[open] ?: return false
        if (line[column] != close) return false
        return close.toString() in languageCloses(open)
    }

    /** 閉じ括弧に対する開き括弧。括弧でなければ null。 */
    fun openerOf(close: Char): Char? = BRACKETS.entries.firstOrNull { it.value == close }?.key

    /**
     * 閉じ括弧を打つ前に、その行の字下げを何へ揃えるか。揃えなくてよければ null。
     *
     * Sora 0.24.6 は `}` を打っても字下げを戻さない。`{|}` の間の Enter は閉じを次の行へ送るが、
     * **自分で改行して `}` を打つと、中身と同じ深さに `}` が立つ**ことがある。
     * VS Code と同じく、カーソルより前が空白だけの行なら、**対応する開き括弧の行と同じ字下げ**にする。
     *
     * 対応は上の行へ数えて探す。**文字列やコメントの中の括弧も数える**ので、
     * `"{"` を挟むと外すことがある ── そのときは揃え先が変わるだけで、打った文字は消えない。
     *
     * @param lineAt 行番号から行の文字を返す（本文を1本の文字列にしないため。Sora の `Content` は行で持つ）
     * @param line   カーソルのある行
     * @param column カーソルの桁（0 始まり）
     * @param close  打たれた文字
     * @param limit  上へ数える行数の上限。大きなファイルで1文字ごとに全文を舐めないため
     */
    fun closingIndent(
        lineAt: (Int) -> CharSequence,
        line: Int,
        column: Int,
        close: Char,
        limit: Int = SCAN_LINES
    ): String? {
        val open = openerOf(close) ?: return null
        val current = lineAt(line)
        if (column > current.length || !isIndent(current, column)) return null
        var depth = 0
        for (row in line - 1 downTo maxOf(0, line - limit)) {
            val text = lineAt(row)
            for (i in text.indices.reversed()) {
                when (text[i]) {
                    close -> depth++
                    open -> if (depth == 0) return text.subSequence(0, indentLength(text)).toString() else depth--
                }
            }
        }
        return null
    }

    private fun isIndent(text: CharSequence, end: Int): Boolean = indentLength(text) >= end

    private fun indentLength(text: CharSequence): Int {
        var end = 0
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return end
    }

    private const val SCAN_LINES = 5_000
}
