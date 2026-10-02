package dev.kirin.kiwa.palette

/**
 * パレットに打たれた文字を「何を引くのか」へ翻訳する。
 *
 * ## なぜ頭の1文字でモードを決めるのか
 *
 * 手本の `fresh` は `Ctrl+P` の1つだけが入口で、**頭の1文字がモードを決める**
 * （無印=ファイル / `>`=コマンド / `:`=行）。入口をモードごとに分けると覚えるものが増える ──
 * 常設バーを4つに絞ったのと同じ理由で、探すものが何であれ押す場所は1箇所にする（案C）。
 *
 * ## ここに画面を持ち込まない
 *
 * モードも絞り込みの語も**打たれた文字だけで決まる**ので、ここは Android を知らない。
 * 実機を出さずに試験できる側に置いてある ── 実機でしか測れないのは
 * 「変換中に何が届くか」であって、届いた文字の解釈ではない。
 */
data class PaletteQuery(val mode: Mode, val term: String) {

    /** [prefix] は**このモードへ移るために打つもの**。押して移る道（モードの帯）もそこから作る。 */
    enum class Mode(val hint: String, val prefix: String) {
        /** 開いているタブと、ツリーの中のファイル。 */
        FILE("ファイル", ""),
        /** 操作の表（`Commands`）そのもの。 */
        COMMAND("コマンド", "$PREFIX_COMMAND"),
        /** 行番号へ飛ぶ。 */
        LINE("行", "$PREFIX_LINE")
    }

    /**
     * 行モードで打たれた行番号。**1 始まり**（画面の状態表示と揃える）。
     * 行モードでない / まだ数字になっていないときは null。
     */
    fun lineNumber(): Int? {
        if (mode != Mode.LINE) return null
        val digits = halfWidth(term)
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
        return digits.toIntOrNull()?.takeIf { it >= 1 }
    }

    companion object {
        const val PREFIX_COMMAND = '>'
        const val PREFIX_LINE = ':'

        /**
         * 全角の prefix。**日本語 IME を通すとこちらで来る**
         * ── 2026-09-07 の実機で `>` を打つと全角の「＞」が未確定のまま入った
         * （Gboard の日本語 QWERTY）。半角だけを見ていると、打てているのに何も変わらない。
         */
        const val PREFIX_COMMAND_WIDE = '＞'
        const val PREFIX_LINE_WIDE = '：'

        fun parse(raw: String): PaletteQuery = when {
            raw.isEmpty() -> PaletteQuery(Mode.FILE, "")
            raw[0] == PREFIX_COMMAND || raw[0] == PREFIX_COMMAND_WIDE ->
                PaletteQuery(Mode.COMMAND, raw.drop(1).trim())
            raw[0] == PREFIX_LINE || raw[0] == PREFIX_LINE_WIDE ->
                PaletteQuery(Mode.LINE, raw.drop(1).trim())
            else -> PaletteQuery(Mode.FILE, raw.trim())
        }

        /**
         * 全角の数字を半角にする。
         *
         * **日本語 IME を通した入力は全角で来ることがある** ── `：４２` と打てたのに
         * 「行が決まらない」とだけ出るのは、打った側からは理由が見えない。
         * prefix の側も同じ事情なので、そちらは [PREFIX_LINE_WIDE] で受けている。
         */
        private fun halfWidth(text: String): String = buildString {
            for (ch in text) {
                if (ch in '０'..'９') append((ch - '０' + '0'.code).toChar()) else append(ch)
            }
        }
    }
}
