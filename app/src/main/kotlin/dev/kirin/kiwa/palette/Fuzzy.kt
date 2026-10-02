package dev.kirin.kiwa.palette

/**
 * 打った文字を飛ばし飛ばしで拾う絞り込み（**部分一致ではなく部分列**）。
 *
 * ## なぜ前方一致でも部分一致でもないのか
 *
 * タブレットでは**打つ量そのものが痛い**。`ui/PaletteView.kt` を出すのに
 * `pv` の2文字で足りる形にしないと、入口を1つにした利益（探し先を覚えなくていい）を
 * 打鍵の多さで打ち消す。手本の `fresh` も VS Code もここは部分列で引く。
 *
 * ## 空白は「かつ」
 *
 * `main kt` のように空白で区切ると、**それぞれが部分列として一致する**ものだけが残る。
 * 部分列は順序に縛られるので、空白を1文字として扱うと `save file` が `file.save` に
 * 当たらない ── 打つ側は「その2語が入っているもの」を探しているのに、順番まで
 * 覚えていないと引けなくなる。
 *
 * ## 引く文字列は呼ぶ側が決める
 *
 * コマンドは日本語の名前（「保存」）と英語の id（`file.save`）の両方で引きたいので、
 * 呼ぶ側が `"保存 file.save"` のように**繋げた1本**を渡す。ここはその文字列だけを見る。
 */
object Fuzzy {

    /** 先頭で当たった。 */
    private const val HEAD = 12

    /** 直前の一致の隣で当たった（＝打った通りに続いている）。 */
    private const val RUN = 8

    /** 区切りの直後で当たった（`file.save` の `s`、`ui/Palette` の `P`）。 */
    private const val AFTER_SEPARATOR = 6

    private const val SEPARATORS = "/._- "

    /**
     * 候補の点。**当たらなければ null** ── 0 点と「当たらない」は別物で、
     * 空の語は全部に当たる（点は付かない）。
     */
    fun score(candidate: String, term: String): Int? {
        val words = term.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return 0
        val haystack = candidate.lowercase()
        var total = 0
        for (word in words) total += scoreWord(haystack, word.lowercase()) ?: return null
        return total
    }

    private fun scoreWord(haystack: String, word: String): Int? {
        var score = 0
        var from = 0
        var previous = -2
        for (needle in word) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return null
            score += when {
                at == 0 -> HEAD
                at == previous + 1 -> RUN
                haystack[at - 1] in SEPARATORS -> AFTER_SEPARATOR
                else -> 0
            }
            // 飛ばした距離のぶんだけ下げる ── 同じ文字が何度も出る長い名前で、
            // 散らばって当たっただけのものが上に来ないように。
            score -= at - from
            previous = at
            from = at + 1
        }
        return score
    }

    /**
     * 点の高い順に並べて上から [limit] 件。**同点は元の並びのまま**
     * （ファイルはツリーの並び、コマンドは表の並びが意味を持っている）。
     */
    fun <T> rank(items: List<T>, term: String, limit: Int, textOf: (T) -> String): List<T> {
        if (term.isEmpty()) return items.take(limit)
        val scored = ArrayList<Pair<T, Int>>(items.size)
        for (item in items) {
            val score = score(textOf(item), term) ?: continue
            scored.add(item to score)
        }
        scored.sortByDescending { it.second }
        return scored.asSequence().take(limit).map { it.first }.toList()
    }
}
