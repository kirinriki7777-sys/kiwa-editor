package dev.kirin.kiwa.file

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 保存（[TextFile.write]）が**書けないときに何も壊さない**こと（2026-10-02 レビューの R1〜R3）。
 *
 * 通常保存・自動保存・別名保存・文字コード変更は、どれも最後に [TextFile.write] を通る。
 * だからここで断れれば、どの経路でも元のファイルと隣のファイルは守られる。
 */
class TextFileWriteTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kiwa-write").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fileWith(name: String, bytes: ByteArray): File =
        File(root, name).also { it.writeBytes(bytes) }

    private fun names(): List<String> = root.list()!!.sorted()

    /** 断られ、元のバイトもフォルダの中身も変わっていないこと。断った理由を返す。 */
    private fun assertRejected(document: TextFile.Document, text: String): String {
        val before = document.file.readBytes()
        val listing = names()
        try {
            TextFile.write(document, text)
            fail("書けない文字があるのに保存できたことになった")
        } catch (e: TextFile.UnencodableException) {
            assertArrayEquals("元のファイルが変わった", before, document.file.readBytes())
            assertEquals("一時ファイルが残った", listing, names())
            return e.message!!
        }
        throw AssertionError("unreachable")
    }

    // ------------------------------------------------------------------
    // R1 ── 書けない文字を黙って `?` にしない
    // ------------------------------------------------------------------

    @Test
    fun `windows-31jのファイルに絵文字を足した保存は断り、ディスクを変えない`() {
        val file = fileWith("sjis.txt", "日本語".toByteArray(charset("windows-31j")))
        val document = TextFile.read(file)
        assertEquals("windows-31j", document.charset.name())
        val reason = assertRejected(document, document.text + "😀")
        assertTrue(reason, reason.contains("😀") && reason.contains("windows-31j"))
    }

    @Test
    fun `ISO-8859-1で開いたファイルへ日本語を足した保存は断る`() {
        val file = fileWith("latin1.txt", byteArrayOf(0x41, 0xE9.toByte()))
        val document = TextFile.read(file)
        assertEquals("ISO-8859-1", document.charset.name())
        assertRejected(document, document.text + "日本語")
    }

    @Test
    fun `対になっていないサロゲートはUTF-8でも断る`() {
        val file = fileWith("utf8.txt", "あ".toByteArray(Charsets.UTF_8))
        val document = TextFile.read(file)
        assertRejected(document, "あ\uD800")
    }

    @Test
    fun `別名保存と文字コード変更の形で作った文書も、書く段で断る`() {
        // 事前の検査（SaveAs.prepare / Encodings.convert）を通らずに作られた文書でも、
        // 最後の口で断れることを見る ── 呼び出し元の検査に頼らない。
        val target = File(root, "new.txt")
        val shiftJis = TextFile.Document(target, "", charset("windows-31j"), TextFile.Bom.NONE, false)
        try {
            TextFile.write(shiftJis, "😀")
            fail("書けない文字があるのに保存できたことになった")
        } catch (e: TextFile.UnencodableException) {
            assertTrue("書けないのに保存先が作られた", !target.exists())
            assertEquals(emptyList<String>(), names())
        }
    }

    @Test
    fun `書ける本文は今までどおりのバイトで書く`() {
        val file = fileWith("sjis.txt", "日本語\r\n".toByteArray(charset("windows-31j")))
        val document = TextFile.read(file)
        TextFile.write(document, "日本語\r\n追記\r\n")
        assertArrayEquals("日本語\r\n追記\r\n".toByteArray(charset("windows-31j")), file.readBytes())
    }

    @Test
    fun `ISO-2022-JPは書き終わりにASCIIへ戻して書く`() {
        val jis = charset("ISO-2022-JP")
        val file = fileWith("jis.txt", "漢字\n".toByteArray(jis))
        val document = TextFile.read(file)
        assertEquals("ISO-2022-JP", document.charset.name())
        TextFile.write(document, "漢字\n仮名")
        assertArrayEquals("漢字\n仮名".toByteArray(jis), file.readBytes())
    }

    // ------------------------------------------------------------------
    // R2 ── BOM 付きの壊れた本文を、触らずに保存しても壊さない
    // ------------------------------------------------------------------

    @Test
    fun `BOMの後ろに読めないバイトがあるファイルは、触らずに保存しても1バイトも変わらない`() {
        val raw = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0xFF.toByte())
        val file = fileWith("bad-bom.txt", raw)
        val document = TextFile.read(file)
        TextFile.write(document, document.text)
        assertArrayEquals(raw, file.readBytes())
    }

    // ------------------------------------------------------------------
    // R3 ── 一時ファイルの名前で隣のファイルを潰さない
    // ------------------------------------------------------------------

    @Test
    fun `一時ファイルと同じ名前の別のファイルは保存しても1バイトも変わらない`() {
        val file = fileWith("normal.txt", "original".toByteArray())
        val sibling = fileWith("normal.txt.kiwa-tmp", "unrelated user data".toByteArray())
        TextFile.write(TextFile.read(file), "edited")
        assertEquals("edited", file.readText())
        assertEquals("unrelated user data", sibling.readText())
        assertEquals(listOf("normal.txt", "normal.txt.kiwa-tmp"), names())
    }

    @Test
    fun `1文字の名前のファイルも保存できる`() {
        val file = fileWith("a", "1".toByteArray())
        TextFile.write(TextFile.read(file), "2")
        assertEquals("2", file.readText())
        assertEquals(listOf("a"), names())
    }

    @Test
    fun `保存のあとに一時ファイルを残さない`() {
        val file = fileWith("a.txt", "1".toByteArray())
        TextFile.write(TextFile.read(file), "2")
        TextFile.write(TextFile.read(file), "3")
        assertEquals("3", file.readText())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun `一時ファイルを作れないときは保存できたことにしない`() {
        val file = fileWith("a.txt", "original".toByteArray())
        val document = TextFile.read(file)
        root.deleteRecursively()
        try {
            TextFile.write(document, "edited")
            fail("書けないのに保存できたことになった")
        } catch (e: java.io.IOException) {
            assertTrue("消したフォルダが作り直された", !root.exists())
        }
    }
}
