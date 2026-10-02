package dev.kirin.kiwa.file

import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * メイン画面から文字コードを変える（`file.encoding`）。
 *
 * 見たいのは2つ ── **開き直しは往復しないバイトを抱えたまま開かない**こと（保存すると元に戻らない）、
 * **別の文字コードで保存すると次に開いたときも同じ文字コードと判別される**こと。
 */
class EncodingsTest {

    private val file = File("sample.txt")
    private val sjis = Charset.forName("windows-31j")

    @Test
    fun `判別が外れたファイルを Shift_JIS で開き直せる`() {
        // 「あ」の Shift_JIS は 82 A0。UTF-8 としては読めないので ISO-8859-1 へ落ちる類のバイト。
        val raw = "あいう".toByteArray(sjis)
        val reopened = Encodings.reopen(file, raw, Encodings.Choice.SHIFT_JIS)
        assertTrue(reopened is Encodings.Reopened.Ready)
        val document = (reopened as Encodings.Reopened.Ready).document
        assertEquals("あいう", document.text)
        assertEquals("windows-31j", document.charset.name())
        assertFalse(document.fellBackToBytes)
        // 触らずに保存すればバイトは1つも変わらない。
        assertArrayEquals(raw, document.encode(document.text))
    }

    @Test
    fun `往復しないバイトがあれば開き直さない`() {
        // Shift_JIS の「あ」は UTF-8 として読むと置換文字に化ける。そのまま保存すると元のバイトが失われる。
        val raw = "あ".toByteArray(sjis)
        val reopened = Encodings.reopen(file, raw, Encodings.Choice.UTF8)
        assertTrue(reopened is Encodings.Reopened.Rejected)
        assertTrue((reopened as Encodings.Reopened.Rejected).reason.contains("UTF-8"))
    }

    @Test
    fun `ISO-8859-1 ならどのバイトでも開ける`() {
        val raw = byteArrayOf(0x82.toByte(), 0xA0.toByte(), 0x00, 0xFF.toByte())
        val reopened = Encodings.reopen(file, raw, Encodings.Choice.LATIN1)
        assertTrue(reopened is Encodings.Reopened.Ready)
        val document = (reopened as Encodings.Reopened.Ready).document
        assertArrayEquals(raw, document.encode(document.text))
    }

    @Test
    fun `その文字コードの BOM だけを外して覚える`() {
        val raw = TextFile.Bom.UTF8.bytes + "abc".toByteArray()
        val asUtf8 = (Encodings.reopen(file, raw, Encodings.Choice.UTF8) as Encodings.Reopened.Ready).document
        assertEquals("abc", asUtf8.text)
        assertEquals(TextFile.Bom.UTF8, asUtf8.bom)
        assertArrayEquals(raw, asUtf8.encode(asUtf8.text))

        // 別の文字コードで開き直すなら、BOM の3バイトも本文として読む（バイトは失わない）。
        val asLatin1 = (Encodings.reopen(file, raw, Encodings.Choice.LATIN1) as Encodings.Reopened.Ready).document
        assertEquals(TextFile.Bom.NONE, asLatin1.bom)
        assertArrayEquals(raw, asLatin1.encode(asLatin1.text))
    }

    @Test
    fun `別の文字コードで保存したものは次に開いたときも同じと判別される`() {
        val utf8 = TextFile.decode(file, "日本語".toByteArray())
        for (choice in Encodings.Choice.entries) {
            if (choice == Encodings.Choice.LATIN1) continue // 日本語は書けない（下の試験）
            val prepared = Encodings.convert(utf8, "日本語", choice)
            assertTrue(choice.name, prepared is SaveAs.Prepared.Ready)
            val document = (prepared as SaveAs.Prepared.Ready).document
            val bytes = document.encode("日本語")
            val back = TextFile.decode(file, bytes)
            assertEquals(choice.name, "日本語", back.text)
            assertEquals(choice.name, choice, Encodings.current(back))
        }
    }

    @Test
    fun `書けない文字があれば保存しない`() {
        val utf8 = TextFile.decode(file, "a".toByteArray())
        val prepared = Encodings.convert(utf8, "a😀", Encodings.Choice.SHIFT_JIS)
        assertTrue(prepared is SaveAs.Prepared.Rejected)
        assertTrue((prepared as SaveAs.Prepared.Rejected).reason.contains("😀"))
    }

    @Test
    fun `状態表示の名前に BOM と判別の失敗が出る`() {
        val bom = TextFile.decode(file, TextFile.Bom.UTF8.bytes + "a".toByteArray())
        assertEquals("UTF-8+BOM", Encodings.label(bom))
        val lost = TextFile.decode(file, byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x81.toByte()))
        assertTrue(Encodings.label(lost).endsWith("（判別できずバイトのまま）"))
    }

    @Test
    fun `開き直しの選択肢は文字コードごとに1つ`() {
        assertEquals(Encodings.REOPEN.size, Encodings.REOPEN.map { it.charsetName }.toSet().size)
        assertTrue(Encodings.Choice.UTF8 in Encodings.REOPEN)
        assertFalse(Encodings.Choice.UTF8_BOM in Encodings.REOPEN)
    }
}
