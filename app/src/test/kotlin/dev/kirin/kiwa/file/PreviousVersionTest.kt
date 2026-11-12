package dev.kirin.kiwa.file

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 上書き前の版を1世代だけ控える（[PreviousVersion]）と、[TextFile.write] の差し替え前の口。 */
class PreviousVersionTest {

    private lateinit var root: File
    private lateinit var work: File
    private lateinit var store: PreviousVersion

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kiwa-prev").toFile()
        work = File(root, "work").also { it.mkdirs() }
        store = PreviousVersion(File(root, "private/previous"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fileWith(name: String, text: String): File =
        File(work, name).also { it.writeBytes(text.toByteArray()) }

    /** ワープロのタブの保存と同じ形（差し替えの直前に控える）。 */
    private fun save(file: File, text: String) {
        TextFile.write(TextFile.read(file), text) { store.keep(it) }
    }

    @Test
    fun `控えの名前は元の絶対パスで決まり、同じパスなら同じ・違えば別`() {
        val a = File(work, "a.txt")
        assertEquals(PreviousVersion.nameFor(a), PreviousVersion.nameFor(File(work, "a.txt")))
        assertNotEquals(PreviousVersion.nameFor(a), PreviousVersion.nameFor(File(work, "b.txt")))
        assertNotEquals(PreviousVersion.nameFor(a), PreviousVersion.nameFor(File(root, "a.txt")))
        assertEquals(store.backupOf(a).name, PreviousVersion.nameFor(a) + ".prev")
    }

    @Test
    fun `上書きの直前の中身が控えに残り、元の隣には何も増えない`() {
        val file = fileWith("note.txt", "いち\n")
        val before = work.list()!!.sorted()
        save(file, "いち\nに\n")
        assertArrayEquals("いち\n".toByteArray(), store.backupOf(file).readBytes())
        assertArrayEquals("いち\nに\n".toByteArray(), file.readBytes())
        assertEquals(before, work.list()!!.sorted())
    }

    @Test
    fun `2回目の保存は控えを置き換える（1世代だけ）`() {
        val file = fileWith("note.txt", "A")
        save(file, "B")
        save(file, "C")
        assertArrayEquals("B".toByteArray(), store.backupOf(file).readBytes())
        assertEquals(1, store.backupOf(file).parentFile.list()!!.size)
        assertEquals("B", store.read(file)!!.text)
    }

    @Test
    fun `控えの文字コードもバイトのまま残す`() {
        val sjis = charset("windows-31j")
        val file = File(work, "sjis.txt").also { it.writeBytes("日本語".toByteArray(sjis)) }
        save(file, "日本語と追記")
        assertArrayEquals("日本語".toByteArray(sjis), store.backupOf(file).readBytes())
        assertEquals("日本語", store.read(file)!!.text)
    }

    @Test
    fun `まだ無いファイルへの保存は控えるものが無い`() {
        val target = File(work, "new.txt")
        val document = TextFile.Document(target, "", Charsets.UTF_8, TextFile.Bom.NONE, false)
        TextFile.write(document, "新しい") { store.keep(it) }
        assertEquals("新しい", target.readText())
        assertFalse(store.backupOf(target).exists())
        assertNull(store.read(target))
    }

    @Test
    fun `控えを作れないときは上書きを止め、元のバイトも隣のファイルも変えない`() {
        val file = fileWith("note.txt", "元")
        // 置き場の場所をファイルで塞いで、控えの置き場を作れなくする。
        val blocked = PreviousVersion(File(root, "blocker").also { it.writeText("x") })
        val listing = work.list()!!.sorted()
        try {
            TextFile.write(TextFile.read(file), "新") { blocked.keep(it) }
            fail("控えを作れないのに上書きした")
        } catch (e: IOException) {
            assertEquals("元", file.readText())
            assertEquals("一時ファイルが残った", listing, work.list()!!.sorted())
        }
    }

    @Test
    fun `文字コードとBOMの変更で元のバイトを控える`() {
        for (choice in listOf(Encodings.Choice.SHIFT_JIS, Encodings.Choice.UTF8_BOM)) {
            val file = fileWith("${choice.name}.txt", "日本語\n")
            val before = file.readBytes()
            val next = (Encodings.convert(TextFile.read(file), "日本語\n", choice) as SaveAs.Prepared.Ready).document
            TextFile.write(next, "日本語\n") { store.keep(it) }
            assertArrayEquals(before, store.backupOf(file).readBytes())
            assertArrayEquals(next.encode("日本語\n"), file.readBytes())
            assertFalse(before.contentEquals(file.readBytes()))
        }
    }

    @Test
    fun `文字コード変更で控えに失敗したら元のバイトと更新時刻を保つ`() {
        val file = fileWith("encoding.txt", "日本語\n")
        val before = file.readBytes()
        val mtime = file.lastModified()
        val blocked = PreviousVersion(File(root, "blocker").also { it.writeText("x") })
        val next = (Encodings.convert(TextFile.read(file), "日本語\n", Encodings.Choice.SHIFT_JIS) as SaveAs.Prepared.Ready).document
        try {
            TextFile.write(next, "日本語\n") { blocked.keep(it) }
            fail("控えを作れないのに上書きした")
        } catch (e: IOException) {
            assertArrayEquals(before, file.readBytes())
            assertEquals(mtime, file.lastModified())
        }
    }

    @Test
    fun `書けない文字で断った保存は前の控えを置き換えない`() {
        val sjis = charset("windows-31j")
        val file = File(work, "sjis.txt").also { it.writeBytes("甲".toByteArray(sjis)) }
        save(file, "乙")
        try {
            save(file, "乙😀")
            fail("書けない文字があるのに保存できた")
        } catch (e: TextFile.UnencodableException) {
            assertEquals("甲", store.read(file)!!.text)
        }
    }
}
