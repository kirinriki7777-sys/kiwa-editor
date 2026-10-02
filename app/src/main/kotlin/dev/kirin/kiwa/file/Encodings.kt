package dev.kirin.kiwa.file

import java.io.File
import java.nio.charset.Charset

/**
 * メイン画面から文字コードを変える（2026-10-02）。**画面を知らない。**
 *
 * VS Code の状態表示の文字コードを押したときと同じ2つを持つ。
 *
 * - **開き直す**（[reopen]）── 判別が外れて化けて見えるファイルを、選んだ文字コードで読み直す。
 *   バイトは動かさない
 * - **別の文字コードで保存する**（[convert]）── 本文はそのまま、書き出すバイトを変える
 *
 * ## 開き直しは往復しなければ断る
 *
 * `TextFile` の的（触っていないファイルを保存してもバイトが変わらない）をここでも守る。
 * 選んだ文字コードで読めないバイトがあると、読み込みで置換文字（U+FFFD）に化け、
 * そのまま保存すると**元のバイトが戻らなくなる**。だから読み直した本文を符号化し直して
 * 元のバイト列と一致しないものは開かない。どのファイルも「ISO-8859-1」なら必ず開ける。
 */
object Encodings {

    /** 保存の選択肢。**BOM の有無も文字コードの一部として選ぶ**（UTF-8 は付き／無しの両方がある）。 */
    enum class Choice(val label: String, val charsetName: String, val bom: TextFile.Bom) {
        UTF8("UTF-8", "UTF-8", TextFile.Bom.NONE),
        UTF8_BOM("UTF-8（BOM 付き）", "UTF-8", TextFile.Bom.UTF8),
        SHIFT_JIS("Shift_JIS（windows-31j）", "windows-31j", TextFile.Bom.NONE),
        EUC_JP("EUC-JP", "EUC-JP", TextFile.Bom.NONE),
        ISO_2022_JP("ISO-2022-JP（JIS）", "ISO-2022-JP", TextFile.Bom.NONE),

        /**
         * UTF-16 は**必ず BOM を付けて書く** ── 付けないと、次に開いたときの判別（`TextFile.decode`）が
         * UTF-16 だと分からず、ISO-8859-1 として開いてしまう。
         */
        UTF16LE("UTF-16LE（BOM 付き）", "UTF-16LE", TextFile.Bom.UTF16LE),
        UTF16BE("UTF-16BE（BOM 付き）", "UTF-16BE", TextFile.Bom.UTF16BE),
        LATIN1("ISO-8859-1", "ISO-8859-1", TextFile.Bom.NONE);

        val charset: Charset get() = Charset.forName(charsetName)
    }

    /**
     * 開き直しの選択肢。**BOM は選ばせない**（ファイルに付いているかどうかは読めば分かる）ので、
     * [Choice] から文字コードだけを重複なく拾う。
     */
    val REOPEN: List<Choice> = Choice.entries.distinctBy { it.charsetName }

    /** 今のファイルがどの選択肢に当たるか。当たらなければ null（Shift_JIS と名乗るファイルなど）。 */
    fun current(document: TextFile.Document): Choice? =
        Choice.entries.firstOrNull { sameCharset(it.charset, document.charset) && it.bom == document.bom }

    /** 状態表示に出す短い名前。 */
    fun label(document: TextFile.Document): String = buildString {
        append(document.charset.name())
        if (document.bom != TextFile.Bom.NONE) append("+BOM")
        if (document.fellBackToBytes) append("（判別できずバイトのまま）")
    }

    sealed class Reopened {
        class Ready(val document: TextFile.Document) : Reopened()

        /** 開き直していない。[reason] はそのまま画面に出す文。 */
        class Rejected(val reason: String) : Reopened()
    }

    /**
     * [raw]（ファイルの中身そのもの）を [choice] の文字コードで読む。
     *
     * BOM は**その文字コードの BOM が先頭にあるときだけ**外して覚える ──
     * UTF-8 の BOM が付いたファイルを Shift_JIS で開き直すなら、その3バイトも本文として読む。
     */
    fun reopen(file: File, raw: ByteArray, choice: Choice): Reopened {
        val charset = choice.charset
        val bom = TextFile.Bom.entries.firstOrNull {
            it.matches(raw) && it.charsetName != null && sameCharset(Charset.forName(it.charsetName), charset)
        } ?: TextFile.Bom.NONE
        val body = raw.copyOfRange(bom.bytes.size, raw.size)
        val text = String(body, charset)
        if (!text.toByteArray(charset).contentEquals(body)) {
            return Reopened.Rejected(
                "${choice.charsetName} として読めないバイトがある ── 開き直していない（保存すると元に戻らなくなるため）"
            )
        }
        return Reopened.Ready(TextFile.Document(file, text, charset, bom, fellBackToBytes = false))
    }

    /**
     * 同じファイルへ [choice] の文字コードで書く形を作る。**書けない文字が1つでもあれば断る**
     * （`SaveAs.prepare` と同じ ── 黙って `?` に置き換わると本文が欠ける）。
     */
    fun convert(document: TextFile.Document, text: String, choice: Choice): SaveAs.Prepared {
        val charset = choice.charset
        val lost = SaveAs.firstUnencodable(text, charset)
        if (lost != null) {
            return SaveAs.Prepared.Rejected("「$lost」は ${choice.charsetName} で書けない ── 保存していない")
        }
        return SaveAs.Prepared.Ready(TextFile.Document(document.file, text, charset, choice.bom, fellBackToBytes = false))
    }

    /** `windows-31j` と `Shift_JIS` は別物として扱う（往復の結果が違う）。名前の大小と別名だけ吸収する。 */
    private fun sameCharset(a: Charset, b: Charset): Boolean = a.name() == b.name()
}
