package dev.kirin.kiwa.file

/**
 * 保存するときの後始末（設定の「行末の空白を消す」「最後に改行を足す」）。**画面を知らない。**
 *
 * ## 本文そのものを直す
 *
 * 書き出すバイトだけ直して画面の本文を残すと、保存した直後から「未保存」が付き、
 * 次に保存するまで画面とファイルが食い違う。VS Code と同じく**本文を直してから書く**。
 *
 * ## 置き換えは1か所にまとめる
 *
 * 行ごとに置き換えると Undo が行の数だけ積まれる。直す範囲の頭から尻までを
 * 1回で置き換える形（[Edit]）にして、**1回の Undo で後始末の前へ戻れる**ようにした。
 *
 * ## 消す空白は半角の空白とタブだけ
 *
 * 全角の空白（U+3000）は日本語の文で意図して置くことがあるので残す。
 * VS Code の `files.trimTrailingWhitespace` も空白とタブだけを見る。
 */
object SaveCleanup {

    /** 元の本文の [start]〜[end] を [replacement] へ置き換える。 */
    data class Edit(val start: Int, val end: Int, val replacement: String)

    /**
     * 後始末した本文。何も変わらなければ [text] そのもの。
     *
     * @param lineSeparator 最後に改行を足すときに使う改行コード（このファイルの流儀）
     */
    fun clean(text: String, trimTrailingWhitespace: Boolean, insertFinalNewline: Boolean, lineSeparator: String): String {
        var out = if (trimTrailingWhitespace) trimLines(text) else text
        // 空のファイルには足さない ── 改行1つだけのファイルを作っても意味が無い（VS Code も同じ）。
        if (insertFinalNewline && out.isNotEmpty() && !out.endsWith('\n') && !out.endsWith('\r')) {
            out += lineSeparator
        }
        return out
    }

    /**
     * [text] を [cleaned] へ変える**最小の1か所**。同じなら null。
     *
     * 頭と尻の一致を削って、残った真ん中だけを置き換える ── 直す行が1行なら置き換えもその行だけで済む。
     */
    fun edit(text: String, cleaned: String): Edit? {
        if (text == cleaned) return null
        var head = 0
        val shorter = minOf(text.length, cleaned.length)
        while (head < shorter && text[head] == cleaned[head]) head++
        var tail = 0
        while (tail < shorter - head &&
            text[text.length - 1 - tail] == cleaned[cleaned.length - 1 - tail]
        ) {
            tail++
        }
        return Edit(head, text.length - tail, cleaned.substring(head, cleaned.length - tail))
    }

    /**
     * 行 [line]・桁 [column] の絶対位置。**桁がその行より長ければ行末へ寄せる**
     * （行末の空白を消した後に、カーソルを元の行へ戻すのに使う）。行が無ければ本文の末尾。
     *
     * 改行は `\r\n` / `\n` / `\r` のどれも1つの区切りとして数える（エンジンと同じ数え方）。
     */
    fun indexOf(text: String, line: Int, column: Int): Int {
        var start = 0
        var current = 0
        while (current < line) {
            val next = nextLineStart(text, start) ?: return text.length
            start = next
            current++
        }
        val end = lineEnd(text, start)
        return start + column.coerceIn(0, end - start)
    }

    private fun trimLines(text: String): String {
        val out = StringBuilder(text.length)
        var start = 0
        while (true) {
            val end = lineEnd(text, start)
            var cut = end
            while (cut > start && (text[cut - 1] == ' ' || text[cut - 1] == '\t')) cut--
            out.append(text, start, cut)
            val next = nextLineStart(text, start) ?: break
            out.append(text, end, next)
            start = next
        }
        return out.toString()
    }

    /** [start] から始まる行の、改行を含まない終わり。 */
    private fun lineEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        return i
    }

    /** 次の行の頭。この行が最後なら null。 */
    private fun nextLineStart(text: String, start: Int): Int? {
        val end = lineEnd(text, start)
        if (end >= text.length) return null
        return if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1
    }
}
