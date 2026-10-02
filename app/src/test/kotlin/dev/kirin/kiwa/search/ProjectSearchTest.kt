package dev.kirin.kiwa.search

import dev.kirin.editoradapter.SearchQuery
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ProjectSearch] の入出力表（発注文の表2・27行、19b を含む）を機械で踏む。
 *
 * 一時ディレクトリへ表の通りのファイルを置いて確かめる。フラグの既定は
 * 表と同じく全部 `false`（大小を区別しない・単語単位でない・正規表現でない）。
 */
class ProjectSearchTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kiwa-search").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String, bytes: ByteArray) {
        val target = File(root, path)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    private fun file(path: String, text: String) = file(path, text.toByteArray(Charsets.UTF_8))

    private fun query(
        pattern: String,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        regex: Boolean = false
    ) = SearchQuery(pattern, caseSensitive, wholeWord, regex)

    // ------------------------------------------------------------------
    // #1〜#4 基本の一致 / 並び / 重なり
    // ------------------------------------------------------------------

    @Test
    fun `1 一致1件の中身が全部合う`() {
        file("a.txt", "hello\nworld\n")
        val outcome = ProjectSearch.search(root, query("world"))
        assertEquals(1, outcome.hits.size)
        val hit = outcome.hits[0]
        assertEquals(File(root, "a.txt"), hit.file)
        assertEquals(2, hit.line)
        assertEquals(0, hit.column)
        assertEquals("world", hit.text)
        assertEquals(0, hit.textStart)
        assertEquals(5, hit.lineLength)
        assertEquals(1, outcome.filesScanned)
    }

    @Test
    fun `2 並びはファイルのパスの辞書順`() {
        file("a.txt", "match\n")
        file("b.txt", "match\n")
        val outcome = ProjectSearch.search(root, query("match"))
        assertEquals(2, outcome.hits.size)
        assertEquals(File(root, "a.txt"), outcome.hits[0].file)
        assertEquals(File(root, "b.txt"), outcome.hits[1].file)
    }

    @Test
    fun `3 同じ行に複数の一致`() {
        file("a.txt", "aXaXa\n")
        val outcome = ProjectSearch.search(root, query("a"))
        assertEquals(3, outcome.hits.size)
        assertEquals(listOf(0, 2, 4), outcome.hits.map { it.column })
        assertTrue(outcome.hits.all { it.line == 1 })
    }

    @Test
    fun `4 一致は重ならない`() {
        file("a.txt", "aaa\n")
        val outcome = ProjectSearch.search(root, query("aa"))
        assertEquals(1, outcome.hits.size)
        assertEquals(0, outcome.hits[0].column)
    }

    // ------------------------------------------------------------------
    // #5〜#10 フラグ（大小 / 単語単位 / 正規表現）
    // ------------------------------------------------------------------

    @Test
    fun `5 大小を区別しない既定では一致する`() {
        file("a.txt", "Hello\n")
        val outcome = ProjectSearch.search(root, query("hello", caseSensitive = false))
        assertEquals(1, outcome.hits.size)
    }

    @Test
    fun `6 大小を区別すると一致しない`() {
        file("a.txt", "Hello\n")
        val outcome = ProjectSearch.search(root, query("hello", caseSensitive = true))
        assertEquals(0, outcome.hits.size)
    }

    @Test
    fun `7 単語単位は部分一致を拾わない`() {
        file("a.txt", "foo foobar\n")
        val outcome = ProjectSearch.search(root, query("foo", wholeWord = true))
        assertEquals(1, outcome.hits.size)
        assertEquals(0, outcome.hits[0].column)
    }

    @Test
    fun `8 単語単位は日本語に境界を見つけられない`() {
        file("a.txt", "検索検索\n")
        val outcome = ProjectSearch.search(root, query("検索", wholeWord = true))
        assertEquals(0, outcome.hits.size)
    }

    @Test
    fun `9 正規表現が読める`() {
        file("a.txt", "hello\nworld\n")
        val outcome = ProjectSearch.search(root, query("w.rld", regex = true))
        assertEquals(1, outcome.hits.size)
        assertEquals(2, outcome.hits[0].line)
        assertEquals(0, outcome.hits[0].column)
    }

    @Test
    fun `10 正規表現が単語単位より勝つ`() {
        file("a.txt", "foobar\n")
        val outcome = ProjectSearch.search(root, query("foo", regex = true, wholeWord = true))
        assertEquals(1, outcome.hits.size)
        assertEquals(0, outcome.hits[0].column)
    }

    // ------------------------------------------------------------------
    // #11〜#12 受け付けない条件
    // ------------------------------------------------------------------

    @Test
    fun `11 壊れた正規表現は error を持つ`() {
        file("a.txt", "anything\n")
        val outcome = ProjectSearch.search(root, query("[", regex = true))
        assertTrue(outcome.hits.isEmpty())
        assertNotNull(outcome.error)
        assertEquals(0, outcome.filesScanned)
    }

    @Test
    fun `12 空文字は引ける条件ではない`() {
        file("a.txt", "anything\n")
        val outcome = ProjectSearch.search(root, query(""))
        assertTrue(outcome.hits.isEmpty())
        assertNull(outcome.error)
        assertEquals(0, outcome.filesScanned)
    }

    // ------------------------------------------------------------------
    // #13〜#16 走査から外れるもの
    // ------------------------------------------------------------------

    @Test
    fun `13 除外ディレクトリの中は見ない`() {
        file(".git/x.txt", "match\n")
        val outcome = ProjectSearch.search(root, query("match"))
        assertTrue(outcome.hits.isEmpty())
        assertEquals(0, outcome.filesScanned)
    }

    @Test
    fun `14 隠しファイルは見る`() {
        file(".bashrc", "match\n")
        val outcome = ProjectSearch.search(root, query("match"))
        assertEquals(1, outcome.hits.size)
    }

    @Test
    fun `15 バイナリは飛ばして数えない`() {
        file("bin.dat", byteArrayOf(0x00) + "match text\n".toByteArray())
        val outcome = ProjectSearch.search(root, query("match"))
        assertTrue(outcome.hits.isEmpty())
        assertEquals(0, outcome.filesScanned)
    }

    @Test
    fun `16 上限を超えるファイルは飛ばして数えない`() {
        file("big.txt", "this line is definitely longer than ten bytes and contains match\n")
        val outcome = ProjectSearch.search(root, query("match"), ProjectSearch.Limits(maxFileBytes = 10))
        assertTrue(outcome.hits.isEmpty())
        assertEquals(0, outcome.filesScanned)
    }

    // ------------------------------------------------------------------
    // #17〜#18 打ち切り（件数 / ファイル数）
    // ------------------------------------------------------------------

    @Test
    fun `17 maxHits に当たったら打ち切る`() {
        file("a.txt", "match match match\n")
        val outcome = ProjectSearch.search(root, query("match"), ProjectSearch.Limits(maxHits = 2))
        assertEquals(2, outcome.hits.size)
        assertTrue(outcome.truncated)
    }

    @Test
    fun `18 maxFiles に当たったら打ち切る`() {
        file("a.txt", "match\n")
        file("b.txt", "match\n")
        file("c.txt", "match\n")
        val outcome = ProjectSearch.search(root, query("match"), ProjectSearch.Limits(maxFiles = 2))
        assertEquals(2, outcome.filesScanned)
        assertTrue(outcome.truncated)
    }

    // ------------------------------------------------------------------
    // #19〜#19b 切れ端の作り方
    // ------------------------------------------------------------------

    @Test
    fun `19 切れ端は一致の周りを40文字手前から取る`() {
        val line = "a".repeat(250) + "MATCH" + "a".repeat(45)
        file("a.txt", line + "\n")
        val outcome = ProjectSearch.search(root, query("MATCH"), ProjectSearch.Limits(maxTextLength = 200))
        assertEquals(1, outcome.hits.size)
        val hit = outcome.hits[0]
        assertEquals(250, hit.column)
        assertEquals(300, hit.lineLength)
        assertEquals(200, hit.text.length)
        assertEquals(100, hit.textStart)
        assertTrue(hit.text.contains("MATCH"))
    }

    @Test
    fun `19b 一致が行頭寄りなら切れ端は0文字目から`() {
        val line = "a".repeat(10) + "MATCH" + "a".repeat(285)
        file("a.txt", line + "\n")
        val outcome = ProjectSearch.search(root, query("MATCH"), ProjectSearch.Limits(maxTextLength = 200))
        assertEquals(1, outcome.hits.size)
        val hit = outcome.hits[0]
        assertEquals(10, hit.column)
        assertEquals(0, hit.textStart)
        assertEquals(200, hit.text.length)
    }

    // ------------------------------------------------------------------
    // #20〜#23 打ち切りの種類 / 木の形
    // ------------------------------------------------------------------

    @Test
    fun `20 cancelled はファイルの区切りで見る`() {
        file("a.txt", "match\n")
        file("b.txt", "match\n")
        file("c.txt", "match\n")
        var calls = 0
        val outcome = ProjectSearch.search(root, query("match"), cancelled = {
            calls++
            calls == 2
        })
        assertTrue(outcome.cancelled)
        assertFalse(outcome.truncated)
    }

    @Test
    fun `21 空のディレクトリは0件で正常終了`() {
        val outcome = ProjectSearch.search(root, query("match"))
        assertTrue(outcome.hits.isEmpty())
        assertEquals(0, outcome.filesScanned)
        assertFalse(outcome.truncated)
        assertFalse(outcome.cancelled)
    }

    @Test
    fun `22 深いディレクトリも再帰して見つける`() {
        file("sub/deep/a.txt", "match\n")
        val outcome = ProjectSearch.search(root, query("match"))
        assertEquals(1, outcome.hits.size)
    }

    @Test
    fun `23 ディレクトリのシンボリックリンクは辿らない`() {
        file("sub/a.txt", "match\n")
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), File(root, "sub").toPath())
        val outcome = ProjectSearch.search(root, query("match"))
        assertEquals(1, outcome.hits.size)
        assertEquals(File(root, "sub/a.txt"), outcome.hits[0].file)
    }

    // ------------------------------------------------------------------
    // #24〜#26 改行 / 文字コード
    // ------------------------------------------------------------------

    @Test
    fun `24 CRLF は行末の CR を含めない`() {
        file("a.txt", "hello\r\nworld\r\n")
        val outcome = ProjectSearch.search(root, query("world"))
        assertEquals(1, outcome.hits.size)
        val hit = outcome.hits[0]
        assertEquals(2, hit.line)
        assertEquals(0, hit.column)
        assertEquals(5, hit.lineLength)
    }

    @Test
    fun `25 UTF-8 BOM は桁をずらさない`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file("a.txt", bom + "hello\n".toByteArray())
        val outcome = ProjectSearch.search(root, query("hello"))
        assertEquals(1, outcome.hits.size)
        assertEquals(1, outcome.hits[0].line)
        assertEquals(0, outcome.hits[0].column)
    }

    @Test
    fun `26 Shift_JIS は TextFile が復号する`() {
        file("a.txt", "日本語\n".toByteArray(charset("windows-31j")))
        val outcome = ProjectSearch.search(root, query("日本語"))
        assertEquals(1, outcome.hits.size)
    }

    // 28 は配線で足した行。画面が一致に印を付けるには**当たった長さ**が要り、
    // それは語の長さとは限らない（正規表現では長くも短くもなる）。
    @Test
    fun `28 一致の長さは語ではなく当たった範囲`() {
        file("a.txt", "private fun button(label)\n")
        val plain = ProjectSearch.search(root, query("button"))
        assertEquals(6, plain.hits[0].length)
        val re = ProjectSearch.search(root, query("f.n +butt.n", regex = true))
        assertEquals(1, re.hits.size)
        assertEquals("private ".length, re.hits[0].column)
        assertEquals("fun button".length, re.hits[0].length)
    }

    // 27 は受入で足した行（発注文の表2には無かった）。**エンジンは CR 単独でも行を割る**
    // （Sora `text/InsertTextHelper.java:88-92`）ので、ここで `\n` だけを数えると
    // 行番号の物差しが2本になり、結果を押して飛んだ先が一致した行でなくなる。
    @Test
    fun `27 CR だけの改行もエンジンと同じく行の区切り`() {
        file("a.txt", "hello\rworld\r")
        val outcome = ProjectSearch.search(root, query("world"))
        assertEquals(1, outcome.hits.size)
        val hit = outcome.hits[0]
        assertEquals(2, hit.line)
        assertEquals(0, hit.column)
        assertEquals(5, hit.lineLength)
    }

    // ------------------------------------------------------------------
    // 2026-10-02 レビューの R6 ── UTF-16 を黙って外さない
    // ------------------------------------------------------------------

    private fun utf16(bom: Int, body: ByteArray): ByteArray =
        byteArrayOf((bom shr 8).toByte(), bom.toByte()) + body

    @Test
    fun `R6 同じ本文はUTF-8でもUTF-16でもShift_JISでも同じ所に当たる`() {
        val text = "前の行\nneedle 日本語\n"
        file("utf8.txt", text.toByteArray(Charsets.UTF_8))
        file("utf16le.txt", utf16(0xFFFE, text.toByteArray(Charsets.UTF_16LE)))
        file("utf16be.txt", utf16(0xFEFF, text.toByteArray(Charsets.UTF_16BE)))
        file("sjis.txt", text.toByteArray(charset("windows-31j")))
        val outcome = ProjectSearch.search(root, query("needle"))
        assertNull(outcome.error)
        assertEquals(4, outcome.filesScanned)
        assertEquals(
            listOf("sjis.txt", "utf16be.txt", "utf16le.txt", "utf8.txt"),
            outcome.hits.map { it.file.name }
        )
        assertTrue(outcome.hits.all { it.line == 2 && it.column == 0 && it.text == "needle 日本語" })
    }

    @Test
    fun `R6 UTF-16のBOMが付いていても、UTF-16として読めないものはバイナリとして外す`() {
        // BOM の後ろが奇数長（途中で切れた文字）。開けば ISO-8859-1 のバイトのままになる物。
        file("broken.bin", utf16(0xFFFE, byteArrayOf(0x6E, 0x00, 0x65)))
        val outcome = ProjectSearch.search(root, query("n"))
        assertTrue(outcome.hits.isEmpty())
        assertEquals(0, outcome.filesScanned)
    }

    // ------------------------------------------------------------------
    // 2026-10-02 レビューの R7 ── 集める段階でもやめる・貯めすぎない
    // ------------------------------------------------------------------

    /** `listFiles` が呼ばれた回数を数えるディレクトリ。中身は [children] をそのまま返す。 */
    private class CountingDir(path: String, private val counter: IntArray, vararg val children: File) : File(path) {
        override fun isDirectory() = true
        override fun listFiles(): Array<File> {
            counter[0]++
            return arrayOf(*children)
        }
    }

    /** 根の下に10個のディレクトリ、それぞれにファイル1本（全部同じ実ファイル）。 */
    private fun tenBranches(counter: IntArray): File {
        file("leaf.txt", "match\n")
        val leaf = File(root, "leaf.txt")
        val branches = Array<File>(10) { CountingDir(File(root, "dir$it").path, counter, leaf) }
        return CountingDir(root.path, counter, *branches)
    }

    @Test
    fun `R7 呼ぶ前からやめさせられていたら1つも列挙しない`() {
        val listings = IntArray(1)
        val outcome = ProjectSearch.search(tenBranches(listings), query("match"), cancelled = { true })
        assertTrue(outcome.cancelled)
        assertEquals(0, listings[0])
        assertEquals(0, outcome.filesScanned)
    }

    @Test
    fun `R7 集めている途中でやめさせられたら次のディレクトリへ入らない`() {
        val listings = IntArray(1)
        val outcome = ProjectSearch.search(tenBranches(listings), query("match"), cancelled = { listings[0] >= 1 })
        assertTrue(outcome.cancelled)
        assertEquals("根だけを列挙して止まる", 1, listings[0])
        assertTrue(outcome.hits.isEmpty())
    }

    @Test
    fun `R7 集める上限を越えて貯めず、打ち切ったと返す`() {
        val listings = IntArray(1)
        val outcome = ProjectSearch.search(
            tenBranches(listings), query("match"), ProjectSearch.Limits(maxListed = 3)
        )
        assertTrue(outcome.truncated)
        assertFalse(outcome.cancelled)
        // 根 + 3本目までのディレクトリ + 4本目（そこで4件目を見て止まる）。
        assertEquals(5, listings[0])
        assertEquals(3, outcome.filesScanned)
    }

    @Test
    fun `R7 上限ちょうどなら打ち切りではない`() {
        val listings = IntArray(1)
        val outcome = ProjectSearch.search(
            tenBranches(listings), query("match"), ProjectSearch.Limits(maxListed = 10)
        )
        assertFalse(outcome.truncated)
        assertEquals(11, listings[0])
        assertEquals(10, outcome.filesScanned)
    }

    @Test
    fun `R7 打ち切って0件のときは当たらないと書かない`() {
        val label = SearchStatus.projectLabel(
            error = null, searching = false, hasQuery = true,
            hits = 0, files = 3, truncated = true, cancelled = false
        )
        assertFalse(label, label == "当たらない")
        assertEquals("当たらない", SearchStatus.projectLabel(null, false, true, 0, 3, false, false))
    }
}
