package dev.kirin.kiwa.palette

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileIndexTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kiwa-index").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String) {
        val target = File(root, path)
        target.parentFile?.mkdirs()
        target.writeText("x")
    }

    private fun names(result: FileIndex.Result): List<String> =
        result.files.map { FileIndex.relativePath(root, it) }

    @Test
    fun `浅い方から集める`() {
        file("a.txt")
        file("deep/b.txt")
        file("deep/deeper/c.txt")
        val found = names(FileIndex.scan(root, showHidden = false))
        assertEquals(listOf("a.txt", "deep/b.txt", "deep/deeper/c.txt"), found)
    }

    @Test
    fun `隠しファイルは設定に従う`() {
        file("visible.txt")
        file(".hidden.txt")
        assertEquals(listOf("visible.txt"), names(FileIndex.scan(root, showHidden = false)))
        assertEquals(2, FileIndex.scan(root, showHidden = true).files.size)
    }

    @Test
    fun `生成物のディレクトリは隠しを出す設定でも外れる`() {
        file("src/Main.kt")
        file("build/generated/Big.kt")
        file("node_modules/pkg/index.js")
        // **上限に達して本物が押し出されるのを防ぐのが目的**なので、
        // 「隠しファイルを出す」を選んでも出さない。
        assertEquals(listOf("src/Main.kt"), names(FileIndex.scan(root, showHidden = true)))
    }

    @Test
    fun `上限で打ち切ったことを黙らない`() {
        for (i in 1..10) file("f$i.txt")
        val result = FileIndex.scan(root, showHidden = false, limit = 4)
        assertEquals(4, result.files.size)
        assertTrue("打ち切ったのに黙ると「そのファイルは無い」と読まれる", result.truncated)
    }

    @Test
    fun `上限に届かなければ打ち切っていない`() {
        file("only.txt")
        assertFalse(FileIndex.scan(root, showHidden = false, limit = 4).truncated)
    }

    @Test
    fun `深さの上限より下は見ない`() {
        file("one/two/three/deep.txt")
        file("top.txt")
        assertEquals(listOf("top.txt"), names(FileIndex.scan(root, showHidden = false, maxDepth = 2)))
    }

    @Test
    fun `ディレクトリでなければ空`() {
        file("a.txt")
        assertEquals(FileIndex.Result.EMPTY, FileIndex.scan(File(root, "a.txt"), showHidden = false))
    }

    @Test
    fun `根の外のファイルは絶対パスのまま出す`() {
        assertEquals("/etc/hosts", FileIndex.relativePath(root, File("/etc/hosts")))
    }

    // ------------------------------------------------------------------
    // 2026-10-02 レビューの R8 ── 深さで省いたことも黙らない
    // ------------------------------------------------------------------

    @Test
    fun `深さの上限ちょうどは見つかり、省いていないと返す`() {
        file("one/two/edge.txt")
        val result = FileIndex.scan(root, showHidden = false, maxDepth = 2)
        assertEquals(listOf("one/two/edge.txt"), names(result))
        assertFalse(result.tooDeep)
        assertEquals("", FileIndex.note(result))
    }

    @Test
    fun `深さの上限より下を省いたら、そう返して注記に出す`() {
        file("one/two/three/deep.txt")
        file("top.txt")
        val result = FileIndex.scan(root, showHidden = false, maxDepth = 2)
        assertTrue("深さで省いたのに黙ると「そのファイルは無い」と読まれる", result.tooDeep)
        assertFalse("件数の打ち切りと混ぜない", result.truncated)
        assertEquals("${FileIndex.MAX_DEPTH} 階層より深い所は見ていない", FileIndex.note(result))
    }

    @Test
    fun `外す名前のディレクトリは深さで省いたことにしない`() {
        file("one/two/build/out.txt")
        val result = FileIndex.scan(root, showHidden = false, maxDepth = 2)
        assertFalse(result.tooDeep)
    }

    @Test
    fun `件数と深さの両方で省いたら両方書く`() {
        // 浅い方から見るので、深さで省くディレクトリ（c）を件数の上限より先に通る並びにする。
        file("a/b/c/deep.txt")
        file("a/b/x.txt")
        file("a/b/y.txt")
        val result = FileIndex.scan(root, showHidden = false, limit = 1, maxDepth = 2)
        assertTrue(result.truncated)
        assertEquals(
            "上限 ${FileIndex.LIMIT} 件で打ち切り / ${FileIndex.MAX_DEPTH} 階層より深い所は見ていない",
            FileIndex.note(result)
        )
    }
}
