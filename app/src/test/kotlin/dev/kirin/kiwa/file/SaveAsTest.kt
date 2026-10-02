package dev.kirin.kiwa.file

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `file.saveAs` の判定（U2）。**実機を出さずに踏める側**をここで全部見る。
 *
 * 見たいのは3つ ── 打ち間違えた道を黙って掘らないこと、**既に在るファイルを訊かずに上書きしないこと**、
 * **書けない文字を黙って `?` にしないこと**。どれも「保存できた顔をして中身を失う」壊れ方だから。
 */
class SaveAsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kiwa-saveas").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun resolve(input: String, current: File? = null) = SaveAs.resolve(root, input, current)

    private fun reject(input: String, current: File? = null): String {
        val result = resolve(input, current)
        assertTrue("断られるはずが通った: $input", result is SaveAs.Target.Rejected)
        return (result as SaveAs.Target.Rejected).reason
    }

    // ------------------------------------------------------------------
    // 行き先
    // ------------------------------------------------------------------

    @Test
    fun `名前だけなら今の根に保存する`() {
        val result = resolve("main.go")
        assertTrue(result is SaveAs.Target.Ready)
        assertEquals(File(root, "main.go"), (result as SaveAs.Target.Ready).file)
    }

    @Test
    fun `絶対の道は根を無視する`() {
        val other = Files.createTempDirectory("kiwa-other").toFile()
        try {
            val result = resolve("${other.absolutePath}/a.txt")
            assertEquals(File(other, "a.txt"), (result as SaveAs.Target.Ready).file)
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `前後の空白は落とす`() {
        assertEquals(File(root, "main.go"), (resolve("  main.go  ") as SaveAs.Target.Ready).file)
    }

    @Test
    fun `空の名前は断る`() {
        assertEquals("名前が要る", reject(""))
        assertEquals("名前が要る", reject("   "))
    }

    @Test
    fun `フォルダ名で終わる道は断る`() {
        assertTrue(reject("docs/").startsWith("フォルダには保存できない"))
    }

    @Test
    fun `親のフォルダが無ければ掘らずに断る`() {
        assertTrue(reject("nowhere/note.md").startsWith("フォルダが無い"))
        assertTrue("黙って掘っている", !File(root, "nowhere").exists())
    }

    @Test
    fun `同じ名前のフォルダが在れば断る`() {
        File(root, "docs").mkdir()
        assertTrue(reject("docs").startsWith("同じ名前のフォルダが在る"))
    }

    @Test
    fun `同じ名前のファイルが在れば上書きを訊く形で返し、中身には触らない`() {
        File(root, "main.go").writeText("package main")
        val result = resolve("main.go")
        assertTrue(result is SaveAs.Target.Overwrite)
        assertEquals("package main", File(root, "main.go").readText())
    }

    @Test
    fun `今開いているファイルそのものならふつうの保存として返す`() {
        val current = File(root, "main.go").apply { writeText("package main") }
        assertTrue(resolve("main.go", current) is SaveAs.Target.Same)
        // 道の書き方が違っても同じファイル。
        assertTrue(resolve("./main.go", current) is SaveAs.Target.Same)
    }

    @Test
    fun `別のファイルを開いていても、在る名前は上書きを訊く`() {
        val current = File(root, "a.txt").apply { writeText("a") }
        File(root, "b.txt").writeText("b")
        assertTrue(resolve("b.txt", current) is SaveAs.Target.Overwrite)
    }

    // ------------------------------------------------------------------
    // 書く形
    // ------------------------------------------------------------------

    private fun ready(prepared: SaveAs.Prepared): TextFile.Document {
        assertTrue(
            "書けるはずが断られた: ${(prepared as? SaveAs.Prepared.Rejected)?.reason}",
            prepared is SaveAs.Prepared.Ready
        )
        return (prepared as SaveAs.Prepared.Ready).document
    }

    @Test
    fun `無題のタブは設定の文字コードで書く`() {
        val target = File(root, "note.txt")
        val document = ready(SaveAs.prepare(target, "日本語\n", null, "UTF-8"))
        TextFile.write(document, "日本語\n")
        assertArrayEquals("日本語\n".toByteArray(Charsets.UTF_8), target.readBytes())
        assertEquals(TextFile.Bom.NONE, document.bom)
    }

    @Test
    fun `開いたファイルの別名保存は元の文字コードとBOMを引き継ぐ`() {
        val original = File(root, "sjis.txt")
        val bytes = "日本語\r\n".toByteArray(charset("Shift_JIS"))
        original.writeBytes(bytes)
        val source = TextFile.read(original)
        assertEquals("Shift_JIS 系として読めていない", true, source.charset.name().let {
            it == "windows-31j" || it == "Shift_JIS"
        })

        val copy = File(root, "copy.txt")
        val document = ready(SaveAs.prepare(copy, source.text, source, "UTF-8"))
        TextFile.write(document, source.text)
        // **触っていない本文を別名で保存したら、バイトが1つも変わらない**（TextFile の目標と同じ）。
        assertArrayEquals(bytes, copy.readBytes())
        // 元のファイルは残っている。
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test
    fun `BOM付きのファイルを別名で保存してもBOMが残る`() {
        val original = File(root, "bom.txt")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc\n".toByteArray()
        original.writeBytes(bytes)
        val source = TextFile.read(original)

        val copy = File(root, "copy.txt")
        TextFile.write(ready(SaveAs.prepare(copy, source.text, source, "UTF-8")), source.text)
        assertArrayEquals(bytes, copy.readBytes())
    }

    @Test
    fun `書けない文字があれば断り、何も書かない`() {
        val target = File(root, "note.txt")
        val prepared = SaveAs.prepare(target, "abc😀def", null, "Shift_JIS")
        assertTrue(prepared is SaveAs.Prepared.Rejected)
        val reason = (prepared as SaveAs.Prepared.Rejected).reason
        // **どの文字が駄目かを言う**（長い本文のどこかにある1文字を探させない）。
        assertTrue(reason, reason.contains("😀"))
        assertTrue("書いてしまっている", !target.exists())
    }

    @Test
    fun `知らない文字コード名は断る`() {
        val prepared = SaveAs.prepare(File(root, "a.txt"), "abc", null, "no-such-charset")
        assertTrue(prepared is SaveAs.Prepared.Rejected)
    }

    @Test
    fun `書けない最初の文字を1文字で返す`() {
        val sjis = charset("Shift_JIS")
        assertEquals(null, SaveAs.firstUnencodable("日本語 abc", sjis))
        assertEquals("😀", SaveAs.firstUnencodable("あ😀い🍣", sjis))
    }
}
