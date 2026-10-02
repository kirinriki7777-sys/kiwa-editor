package dev.kirin.kiwa.file

import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * テキストファイルの読み書き。
 *
 * ## 目標は1つ ── 触っていないファイルを保存したとき、バイトが1つも変わらないこと
 *
 * 文字コードの推定は当たり外れがあるものなので、**当てにいかず、確かめる**。
 * 候補を順に試し、**復号したものを符号化し直して元のバイト列と一致した最初の候補**を採る
 * （[detect]）。一致しなければその候補は捨てる。最後の候補 ISO-8859-1 はバイトを 1:1 で
 * 写すので必ず一致する ── つまり**どんな入力でも往復が保証される**。
 * 文字化けして見えることはあっても、保存して壊すことはない。
 *
 * ## 改行コードは触らない
 *
 * Sora は改行コードを行ごとに保持して書き戻す（`ContentRoundTripTest` で実測）。
 * ここで LF へ正規化すると、CRLF のファイルが保存時に全行 diff を出す。**正規化しない。**
 */
object TextFile {

    /** 開けるファイルの上限。これを超えるものはエディタの用途から外れる。 */
    const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * 試す順。UTF-8 を先に置くのは、これが一致するなら他を試す意味が無いため。
     * 末尾の ISO-8859-1 は「バイトをそのまま char へ写す」ので必ず往復する＝最後の砦。
     */
    private val CANDIDATES: List<String> =
        listOf("UTF-8", "windows-31j", "Shift_JIS", "EUC-JP", "ISO-2022-JP", "ISO-8859-1")

    enum class Bom(val bytes: ByteArray, val charsetName: String?) {
        NONE(byteArrayOf(), null),
        UTF8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "UTF-8"),
        UTF16LE(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16LE"),
        UTF16BE(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), "UTF-16BE");

        fun matches(bytes: ByteArray): Boolean =
            this != NONE && bytes.size >= this.bytes.size &&
                this.bytes.indices.all { bytes[it] == this.bytes[it] }
    }

    /**
     * 読み込んだ結果。保存するときに同じ [charset] と [bom] を使う ── これが往復の条件。
     */
    class Document(
        val file: File,
        val text: String,
        val charset: Charset,
        val bom: Bom,
        /** 復号が往復しなかったので ISO-8859-1 へ落ちた場合 true。表示だけに使う。 */
        val fellBackToBytes: Boolean
    ) {
        /**
         * **書けない文字が1つでもあれば [UnencodableException] で断る。**
         *
         * `String.toByteArray(charset)` は表せない文字を黙って `?` に置き換える ──
         * それでは保存が成功したように見えて本文が欠ける（windows-31j のファイルに絵文字を足した時など）。
         * 保存の道は全部ここを通るので、通常保存・自動保存・別名保存・文字コード変更のどれでも同じに断れる。
         */
        fun encode(newText: String): ByteArray {
            val encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = try {
                encoder.encode(CharBuffer.wrap(newText))
            } catch (e: CharacterCodingException) {
                throw UnencodableException(SaveAs.firstUnencodable(newText, charset), charset)
            }
            val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (bom == Bom.NONE) return body
            return bom.bytes + body
        }
    }

    class TooLargeException(val size: Long) : IOException("file is $size bytes")

    /** [Document.encode] が断った。**何も書いていない。** [message] はそのまま画面に出す文。 */
    class UnencodableException(character: String?, charset: Charset) : IOException(
        (if (character != null) "「$character」は ${charset.name()} で書けない" else "${charset.name()} で書けない文字がある") +
            " ── 保存していない（文字コードの札から別の文字コードで保存できる）"
    )

    /**
     * このファイルが使っている改行コード。**多数派を採る**。
     *
     * 新しく足す行をこれに揃えないと、CRLF のファイルに1行書いただけで改行が混ざる
     * （実機で確認した）。**判断できないとき（改行が1つも無い / 空）だけ [fallback] を使う** ──
     * ここが設定の「改行コードの既定」が効く唯一の場所で、
     * 改行が在るファイルの流儀は設定では動かせない。
     */
    @JvmOverloads
    fun dominantLineSeparator(text: String, fallback: String = "\n"): String {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' ->
                    if (i + 1 < text.length && text[i + 1] == '\n') {
                        crlf++
                        i++
                    } else {
                        cr++
                    }
                '\n' -> lf++
            }
            i++
        }
        return when {
            crlf >= lf && crlf >= cr && crlf > 0 -> "\r\n"
            cr > lf && cr > 0 -> "\r"
            lf > 0 -> "\n"
            else -> fallback
        }
    }

    @JvmOverloads
    fun read(file: File, maxBytes: Long = MAX_BYTES.toLong(), preferredCharset: String = "UTF-8"): Document {
        val size = file.length()
        if (size > maxBytes) throw TooLargeException(size)
        val raw = file.readBytes()
        return decode(file, raw, preferredCharset)
    }

    internal fun decode(
        file: File,
        raw: ByteArray,
        preferredCharset: String = "UTF-8",
        bomless: Boolean = false,
    ): Document {
        val bom = if (bomless) Bom.NONE else Bom.entries.firstOrNull { it.matches(raw) } ?: Bom.NONE
        val body = raw.copyOfRange(bom.bytes.size, raw.size)

        // **BOM は手掛かりであって、本文が正しい証明ではない。** BOM の後ろに読めないバイトがあると
        // 置換文字（U+FFFD）に化け、触らずに保存しただけで元のバイトが戻らなくなる。
        // ほかの候補と同じく往復で検算し、外れたら BOM も本文の一部として下の候補へ回す（バイトは失わない）。
        bom.charsetName?.let { name ->
            val charset = Charset.forName(name)
            val text = String(body, charset)
            if (text.toByteArray(charset).contentEquals(body)) {
                return Document(file, text, charset, bom, false)
            }
            return decode(file, raw, preferredCharset, bomless = true)
        }

        // **どの候補でも往復してしまうファイル**（＝ ASCII だけ）は、名乗るものを選べる。
        // ここでだけ設定の「文字コードの既定」が効く ── そのファイルへ日本語を打って
        // 保存したときに何になるかが決まる。
        //
        // **候補の順そのものは動かさない。** 既定を先頭へ持ってくると、
        // UTF-8 の日本語ファイルが Shift_JIS として往復してしまうことがある
        // （「ああ」= E3 81 82 E3 81 82 は SJIS の2バイト対として3組に割れ、符号化し直すと元に戻る）。
        // 往復の検算はそこを弾けないので、順を触らずに ASCII だけを分ける。
        if (body.all { it >= 0 }) {
            // **ISO-2022-JP（JIS）もバイトは全部 ASCII** なので、ここで先に拾わないと
            // 既定の文字コード（UTF-8）が往復してしまい、エスケープ列ごと化けて開く（2026-10-02 まではそうだった）。
            // 漢字へ切り替えるエスケープ列が在るときだけ試し、往復しなければ下の既定へ回す。
            if (hasJisKanjiEscape(body)) {
                val jis = Charset.forName("ISO-2022-JP")
                val text = String(body, jis)
                if (text.toByteArray(jis).contentEquals(body)) {
                    return Document(file, text, jis, Bom.NONE, false)
                }
            }
            val charset = runCatching { Charset.forName(preferredCharset) }.getOrNull()
            if (charset != null) {
                val text = String(body, charset)
                if (text.toByteArray(charset).contentEquals(body)) {
                    return Document(file, text, charset, Bom.NONE, false)
                }
            }
        }

        for (name in CANDIDATES) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val text = String(body, charset)
            // 推定を信じず、往復で検算する。ここが「保存して壊さない」の根拠。
            if (text.toByteArray(charset).contentEquals(body)) {
                return Document(file, text, charset, Bom.NONE, name == "ISO-8859-1")
            }
        }
        // ISO-8859-1 は原理的にここへ来ないが、来たとしてもバイトは失わない。
        val fallback = Charsets.ISO_8859_1
        return Document(file, String(body, fallback), fallback, Bom.NONE, true)
    }

    /**
     * ISO-2022-JP の「漢字へ切り替える」エスケープ列（`ESC $ B` / `ESC $ @`）が在るか。
     *
     * 見るのは漢字側だけ ── `ESC ( B`（ASCII へ戻す）だけのファイルは、JIS と名乗らせる理由が無い。
     * `ESC $ @`（旧 JIS）のファイルは Java の符号化が `ESC $ B` で書き直すので往復せず、
     * 呼び出し側の検算で落ちて今までどおりの既定へ回る（バイトは失わない）。
     */
    private fun hasJisKanjiEscape(body: ByteArray): Boolean {
        for (i in 0 until body.size - 2) {
            if (body[i] == ESC && body[i + 1] == '$'.code.toByte() &&
                (body[i + 2] == 'B'.code.toByte() || body[i + 2] == '@'.code.toByte())
            ) {
                return true
            }
        }
        return false
    }

    private const val ESC: Byte = 0x1B

    /**
     * 一時ファイルへ書いてから差し替える。
     * 途中で失敗したときに、元のファイルが半分書けた状態で残るのを避けるため。
     *
     * **符号化は一時ファイルを作る前に済ませる** ── 書けない文字で断るときに、何も残さない。
     *
     * **一時ファイルの名前は毎回新しく、既に在る名前は使わない**（`createTempFile` が排他的に作る）。
     * 決まった名前だと、同じ名前の別のファイルを上書きして rename で消してしまう。
     * 片付けるのも自分が作ったものだけ。頭の `.` は、`createTempFile` が3文字未満の接頭辞を断るため
     * （1文字の名前のファイルでも保存できるように）。
     */
    fun write(document: Document, newText: String) {
        val bytes = document.encode(newText)
        val target = document.file
        val temp = File.createTempFile(".${target.name}.", ".kiwa-tmp", target.parentFile)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                // 同じディレクトリなら普通は成功する。失敗したら中身を写す。
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
