package dev.kirin.kiwa.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **触っていないファイルを保存したらバイトが1つも変わらない**ことを、
 * 文字コードと BOM と改行コードの組み合わせで確かめる。
 *
 * ここが崩れると、エディタで開いて閉じただけで git の差分が出る ──
 * 実際に使えるかどうかを分ける線。
 */
class TextFileTest {

    private val dummy = File("/dev/null")

    private fun assertByteIdentity(raw: ByteArray, message: String) {
        val doc = TextFile.decode(dummy, raw)
        assertTrue(
            "$message: 復号→符号化でバイトが変わった (charset=${doc.charset})",
            doc.encode(doc.text).contentEquals(raw)
        )
    }

    @Test
    fun utf8Lf() = assertByteIdentity("日本語\nコード\n".toByteArray(Charsets.UTF_8), "UTF-8 / LF")

    @Test
    fun utf8Crlf() = assertByteIdentity("日本語\r\nコード\r\n".toByteArray(Charsets.UTF_8), "UTF-8 / CRLF")

    @Test
    fun utf8WithBom() {
        val raw = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "日本語\n".toByteArray(Charsets.UTF_8)
        assertByteIdentity(raw, "UTF-8 BOM 付き")
        assertEquals(TextFile.Bom.UTF8, TextFile.decode(dummy, raw).bom)
        assertEquals("日本語\n", TextFile.decode(dummy, raw).text)
    }

    @Test
    fun shiftJis() {
        val raw = "日本語のファイル\r\n二行目\r\n".toByteArray(charset("windows-31j"))
        assertByteIdentity(raw, "Shift_JIS")
        assertEquals("日本語のファイル\r\n二行目\r\n", TextFile.decode(dummy, raw).text)
    }

    @Test
    fun noTrailingNewline() = assertByteIdentity("末尾に改行なし".toByteArray(Charsets.UTF_8), "末尾改行なし")

    @Test
    fun emptyFile() = assertByteIdentity(ByteArray(0), "空ファイル")

    @Test
    fun surrogatePairs() = assertByteIdentity("𩸽 と 😀\n".toByteArray(Charsets.UTF_8), "サロゲートペア")

    /** どの文字コードでも復号できないバイト列。往復だけは必ず守られる。 */
    @Test
    fun undecodableBytesStillRoundTrip() {
        val raw = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x41)
        assertByteIdentity(raw, "復号できないバイト列")
    }

    // ------------------------------------------------------------------
    // 改行コード ── 新しく足す行を元のファイルに合わせるため
    // ------------------------------------------------------------------

    @Test
    fun dominantSeparatorOfCrlfFile() =
        assertEquals("\r\n", TextFile.dominantLineSeparator("a\r\nb\r\nc\r\n"))

    @Test
    fun dominantSeparatorOfLfFile() =
        assertEquals("\n", TextFile.dominantLineSeparator("a\nb\nc\n"))

    @Test
    fun dominantSeparatorOfCrFile() =
        assertEquals("\r", TextFile.dominantLineSeparator("a\rb\rc\r"))

    /** 混在しているときは多数派。ここで LF を選ぶと CRLF 側がさらに増える。 */
    @Test
    fun dominantSeparatorOfMixedFile() =
        assertEquals("\r\n", TextFile.dominantLineSeparator("a\r\nb\r\nc\n"))

    /** 改行が1つも無いファイルは判断できない。LF に倒す。 */
    @Test
    fun dominantSeparatorWithoutAnyNewline() =
        assertEquals("\n", TextFile.dominantLineSeparator("no newline at all"))

    @Test
    fun dominantSeparatorOfEmptyFile() = assertEquals("\n", TextFile.dominantLineSeparator(""))

    /** CR を CRLF の一部として二重に数えない。 */
    @Test
    fun crlfIsNotCountedAsCr() =
        assertEquals("\r\n", TextFile.dominantLineSeparator("a\r\nb\r\n"))

    /** UTF-8 として読めるものを Shift_JIS だと誤判定しない。 */
    @Test
    fun utf8IsPreferredOverShiftJis() {
        val doc = TextFile.decode(dummy, "あいうえお\n".toByteArray(Charsets.UTF_8))
        assertEquals(Charsets.UTF_8, doc.charset)
        assertEquals("あいうえお\n", doc.text)
    }

    // ------------------------------------------------------------------
    // 文字コードの既定（E8）── どの候補でも往復するファイルだけに効く
    // ------------------------------------------------------------------

    /**
     * ASCII だけのファイルは全部の候補が往復するので、**何を名乗るかは選べる**。
     * ここが「文字コードの既定」の唯一の効き所で、次に日本語を打って保存したときの形を決める。
     */
    @Test
    fun asciiOnlyFileTakesThePreferredCharset() {
        val doc = TextFile.decode(dummy, "hello\n".toByteArray(Charsets.US_ASCII), "windows-31j")
        assertEquals("windows-31j", doc.charset.name().lowercase())
        assertEquals("hello\n", doc.text)
    }

    /**
     * **既定を Shift_JIS にしても、UTF-8 の日本語ファイルは UTF-8 のまま読む。**
     *
     * これが崩れると被害が大きい ── 「ああ」= `E3 81 82 E3 81 82` は Shift_JIS の
     * 2バイト対3組としても成立し、符号化し直すと元のバイト列に戻る。
     * つまり**往復の検算では弾けない**ので、候補の順そのものを動かさないことで避けている。
     */
    @Test
    fun preferredCharsetDoesNotOverrideRealUtf8() {
        val raw = "ああ".toByteArray(Charsets.UTF_8)
        val doc = TextFile.decode(dummy, raw, "windows-31j")
        assertEquals(Charsets.UTF_8, doc.charset)
        assertEquals("ああ", doc.text)
    }

    /** 既定を指定しても、往復しない候補は選ばれない（EUC-JP の既定 × UTF-8 の中身）。 */
    @Test
    fun preferredCharsetIsIgnoredWhenItDoesNotRoundTrip() {
        val raw = "日本語テキスト".toByteArray(Charsets.UTF_8)
        val doc = TextFile.decode(dummy, raw, "EUC-JP")
        assertTrue(
            "復号→符号化でバイトが変わった (charset=${doc.charset})",
            doc.encode(doc.text).contentEquals(raw)
        )
    }

    /** 改行が1つも無いときだけ、設定の既定が効く。 */
    @Test
    fun fallbackSeparatorAppliesOnlyWithoutNewlines() {
        assertEquals("\r\n", TextFile.dominantLineSeparator("one line", "\r\n"))
        assertEquals("\r\n", TextFile.dominantLineSeparator("", "\r\n"))
        // 改行が在れば、そのファイルの流儀が勝つ
        assertEquals("\n", TextFile.dominantLineSeparator("a\nb\n", "\r\n"))
    }

    // ------------------------------------------------------------------
    // ISO-2022-JP（2026-10-02）── バイトが全部 ASCII でも JIS と分かる
    // ------------------------------------------------------------------

    private val jis = java.nio.charset.Charset.forName("ISO-2022-JP")

    @Test
    fun iso2022JpIsDetectedAlthoughAllBytesAreAscii() {
        val raw = "日本語\nabc\n".toByteArray(jis)
        assertTrue("材料が ASCII だけであること", raw.all { it >= 0 })
        val doc = TextFile.decode(dummy, raw)
        assertEquals("ISO-2022-JP", doc.charset.name())
        assertEquals("日本語\nabc\n", doc.text)
        assertByteIdentity(raw, "ISO-2022-JP")
    }

    @Test
    fun iso2022JpWinsOverThePreferredCharset() {
        // 既定を UTF-8 にしていても（＝既定のまま）JIS を UTF-8 と名乗らせない。
        val raw = "かな".toByteArray(jis)
        assertEquals("ISO-2022-JP", TextFile.decode(dummy, raw, "UTF-8").charset.name())
    }

    @Test
    fun oldJisEscapeFallsBackWithoutLosingBytes() {
        // `ESC $ @`（旧 JIS）は Java が `ESC $ B` で書き直すので往復しない。今までどおり既定で開き、バイトは保つ。
        val raw = byteArrayOf(0x1B, '$'.code.toByte(), '@'.code.toByte(), 0x46, 0x7C, 0x1B, '('.code.toByte(), 'B'.code.toByte())
        val doc = TextFile.decode(dummy, raw)
        assertEquals("UTF-8", doc.charset.name())
        assertByteIdentity(raw, "旧 JIS のエスケープ")
    }

    @Test
    fun asciiReturnEscapeAloneIsNotJis() {
        // ASCII へ戻すエスケープだけでは JIS と名乗らせない（漢字が1つも無い）。
        val raw = byteArrayOf(0x1B, '('.code.toByte(), 'B'.code.toByte(), 'a'.code.toByte())
        assertEquals("UTF-8", TextFile.decode(dummy, raw).charset.name())
    }

    // ------------------------------------------------------------------
    // BOM は手掛かりであって証明ではない（2026-10-02 レビューの R2）
    // ------------------------------------------------------------------

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun utf8BomFollowedByInvalidByteRoundTrips() {
        val raw = bytes(0xEF, 0xBB, 0xBF, 0xFF)
        assertByteIdentity(raw, "UTF-8 BOM + 不正バイト")
        assertEquals(TextFile.Bom.NONE, TextFile.decode(dummy, raw).bom)
    }

    @Test
    fun utf8BomFollowedByTruncatedCharacterRoundTrips() =
        assertByteIdentity(bytes(0xEF, 0xBB, 0xBF, 0xE3, 0x81), "UTF-8 BOM + 途中で切れた文字")

    @Test
    fun utf16BomWithOddLengthBodyRoundTrips() {
        assertByteIdentity(bytes(0xFF, 0xFE, 0x41, 0x00, 0x42), "UTF-16LE BOM + 奇数長")
        assertByteIdentity(bytes(0xFE, 0xFF, 0x00, 0x41, 0x00), "UTF-16BE BOM + 奇数長")
    }

    @Test
    fun utf16BomWithLoneSurrogateRoundTrips() {
        assertByteIdentity(bytes(0xFF, 0xFE, 0x00, 0xD8, 0x41, 0x00), "UTF-16LE BOM + 対の無いサロゲート")
        assertByteIdentity(bytes(0xFE, 0xFF, 0xD8, 0x00, 0x00, 0x41), "UTF-16BE BOM + 対の無いサロゲート")
    }

    @Test
    fun validBomFilesStillKeepTheirBom() {
        val le = bytes(0xFF, 0xFE) + "日本語\n".toByteArray(Charsets.UTF_16LE)
        val be = bytes(0xFE, 0xFF) + "日本語\n".toByteArray(Charsets.UTF_16BE)
        assertByteIdentity(le, "UTF-16LE BOM")
        assertByteIdentity(be, "UTF-16BE BOM")
        assertEquals(TextFile.Bom.UTF16LE, TextFile.decode(dummy, le).bom)
        assertEquals(TextFile.Bom.UTF16BE, TextFile.decode(dummy, be).bom)
        assertEquals("日本語\n", TextFile.decode(dummy, be).text)
    }

    @Test
    fun bomOnlyFilesKeepTheirBom() {
        assertEquals(TextFile.Bom.UTF8, TextFile.decode(dummy, bytes(0xEF, 0xBB, 0xBF)).bom)
        assertEquals(TextFile.Bom.UTF16LE, TextFile.decode(dummy, bytes(0xFF, 0xFE)).bom)
        assertEquals(TextFile.Bom.UTF16BE, TextFile.decode(dummy, bytes(0xFE, 0xFF)).bom)
    }
}
