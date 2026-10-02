package dev.kirin.kiwa.file

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 保存先のフォルダを選ぶ画面の中身（2026-10-02）。
 *
 * 見たいのは「フォルダだけが出るか」と「読めないフォルダを空に見せないか」の2つ。
 */
class FolderChoiceTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kiwa-folder").toFile()
    }

    @After
    fun tearDown() {
        root.setReadable(true)
        root.deleteRecursively()
    }

    @Test
    fun `フォルダだけを名前順に出す`() {
        File(root, "src").mkdir()
        File(root, "Docs").mkdir()
        File(root, "note.md").writeText("")
        val listing = FolderChoice.list(root, showHidden = true)
        assertEquals(listOf("Docs", "src"), listing.folders.map { it.name })
        assertTrue(listing.readable)
        assertEquals(root.parentFile, listing.parent)
    }

    @Test
    fun `隠しフォルダは設定に従う`() {
        File(root, ".git").mkdir()
        File(root, "app").mkdir()
        assertEquals(listOf("app"), FolderChoice.list(root, showHidden = false).folders.map { it.name })
        assertEquals(listOf(".git", "app"), FolderChoice.list(root, showHidden = true).folders.map { it.name })
    }

    @Test
    fun `空のフォルダは読めたうえで空`() {
        val listing = FolderChoice.list(root, showHidden = true)
        assertTrue(listing.folders.isEmpty())
        assertTrue(listing.readable)
    }

    @Test
    fun `読めないフォルダは空と分けて返す`() {
        val missing = File(root, "gone")
        val listing = FolderChoice.list(missing, showHidden = true)
        assertTrue(listing.folders.isEmpty())
        assertFalse(listing.readable)
    }

    @Test
    fun `入口は在るものだけを重ねずに並べる`() {
        val sd = File(root, "sd").apply { mkdir() }
        val places = FolderChoice.places(
            listOf(
                FolderChoice.Place("内部共有ストレージ", root),
                FolderChoice.Place("SD カード", sd),
                FolderChoice.Place("SD カード（重複）", File(sd.absolutePath)),
                FolderChoice.Place("外した SD カード", File(root, "removed")),
            )
        )
        assertEquals(listOf("内部共有ストレージ", "SD カード"), places.map { it.label })
    }
}
