package dev.kirin.kiwa.file

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `file.new` の判定（E11）。**実機を出さずに踏める側**をここで全部見る。
 *
 * 見たいのは「作る前に断れているか」── 打ち間違えた道を黙って掘らないこと、
 * 既に在るものを上書きしないことの2つが、この機能の壊れ方だから。
 */
class NewFileTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kiwa-new").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun reject(input: String): String {
        val result = NewFile.resolve(root, input)
        assertTrue("断られるはずが通った: $input", result is NewFile.Result.Rejected)
        return (result as NewFile.Result.Rejected).reason
    }

    private fun ready(input: String): File {
        val result = NewFile.resolve(root, input)
        assertTrue(
            "通るはずが断られた: $input（${(result as? NewFile.Result.Rejected)?.reason}）",
            result is NewFile.Result.Ready
        )
        return (result as NewFile.Result.Ready).file
    }

    // ------------------------------------------------------------------
    // 通る道
    // ------------------------------------------------------------------

    @Test
    fun `名前だけなら今の根に作る`() {
        assertEquals(File(root, "main.go"), ready("main.go"))
    }

    @Test
    fun `相対の道はその先に作る`() {
        File(root, "docs").mkdir()
        assertEquals(File(root, "docs/note.md"), ready("docs/note.md"))
    }

    @Test
    fun `絶対の道は根を無視する`() {
        val other = Files.createTempDirectory("kiwa-other").toFile()
        try {
            assertEquals(File(other, "a.txt"), ready("${other.absolutePath}/a.txt"))
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `前後の空白は落とす`() {
        assertEquals(File(root, "main.go"), ready("  main.go  "))
    }

    @Test
    fun `頭が点でも作れる`() {
        // `.gitignore` を作れないと困る。隠しファイルは名前として正しい。
        assertEquals(File(root, ".gitignore"), ready(".gitignore"))
    }

    @Test
    fun `日本語の名前も通す`() {
        // 名前の欄に FORCE_ASCII を当てていないので、日本語がそのまま来る。
        assertEquals(File(root, "覚え書き.md"), ready("覚え書き.md"))
    }

    // ------------------------------------------------------------------
    // 断る道
    // ------------------------------------------------------------------

    @Test
    fun `空の名前は断る`() {
        assertEquals("名前が要る", reject(""))
        assertEquals("名前が要る", reject("   "))
    }

    @Test
    fun `フォルダは作らない`() {
        assertTrue(reject("docs/").contains("フォルダは作らない"))
    }

    @Test
    fun `親のフォルダが無ければ掘らずに断る`() {
        val reason = reject("nowhere/note.md")
        assertTrue(reason, reason.startsWith("フォルダが無い"))
        assertTrue("黙って掘っている", !File(root, "nowhere").exists())
    }

    @Test
    fun `同じ名前のファイルが在れば断る`() {
        File(root, "main.go").writeText("package main")
        assertTrue(reject("main.go").startsWith("同じ名前のものが在る"))
        // **中身が消えていない**ことまで見る（上書きが一番戻せない）。
        assertEquals("package main", File(root, "main.go").readText())
    }

    @Test
    fun `同じ名前のフォルダが在れば断る`() {
        File(root, "docs").mkdir()
        assertTrue(reject("docs").startsWith("同じ名前のものが在る"))
    }

    // ------------------------------------------------------------------
    // 実際に作る
    // ------------------------------------------------------------------

    @Test
    fun `作ると空のファイルができる`() {
        val target = ready("main.go")
        assertNull(NewFile.create(target))
        assertTrue(target.isFile)
        assertEquals(0L, target.length())
    }

    @Test
    fun `判定の後に誰かが作っていたら理由を返す`() {
        // 母艦から Syncthing で届く、という起き方がある。
        val target = ready("main.go")
        target.writeText("先に在った")
        val reason = NewFile.create(target)
        assertTrue("$reason", reason != null && reason.startsWith("同じ名前のものが在る"))
        assertEquals("先に在った", target.readText())
    }
}
