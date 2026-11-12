package dev.kirin.kiwa.view

/**
 * ワープロのモードの文字数。数えないのは改行（`\n` `\r`）・半角空白・全角空白（U+3000）・タブ。
 * 1文字はコードポイント1つ（サロゲート対は1文字）。
 *
 * 打つたびに範囲を頭から数える素朴な作り。上限は数万字まで（5,000 行の見本で打った時の遅れは
 * 段1 の報告書に測った値がある）。重くなったら、変更の差分だけを足し引きする形に替える。
 */
object CharCount {

    /**
     * [from] 以上 [to] 未満のうち、数える文字の数。[exclude] は数えない範囲
     * （変換中の未確定の文字。確定するまで数に入れない）。範囲は文字の位置（UTF-16）で渡す。
     */
    fun count(text: CharSequence, from: Int = 0, to: Int = text.length, exclude: IntRange? = null): Int {
        val end = to.coerceIn(0, text.length)
        var i = from.coerceIn(0, end)
        var n = 0
        while (i < end) {
            if (exclude != null && i in exclude) {
                i = exclude.last + 1
                continue
            }
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (!isIgnored(cp)) n++
        }
        return n
    }

    private fun isIgnored(cp: Int): Boolean =
        cp == '\n'.code || cp == '\r'.code || cp == ' '.code || cp == '\t'.code || cp == 0x3000
}
