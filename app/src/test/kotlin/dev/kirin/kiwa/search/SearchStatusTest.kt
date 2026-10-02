package dev.kirin.kiwa.search

import dev.kirin.editoradapter.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「探している最中」と「当たらない」を分ける**ことを機械で踏む（E11 の受入の一部）。
 *
 * E10b の欠陥3件目は、ログに「候補 0 件」としか出ず**それが正常な検索結果に見えた**ため、
 * `screencap` で入力欄を見るまで分からなかった。検索は 0 件が正常値になる機能なので、
 * 同じ穴が同じ形で空く ── ここが空かないことを試験にしておく。
 */
class SearchStatusTest {

    private fun label(
        error: String? = null,
        searching: Boolean = false,
        hasQuery: Boolean = true,
        count: Int = 0,
        index: Int = 0
    ) = SearchStatus.label(error, searching, hasQuery, count, index)

    // ------------------------------------------------------------------
    // ★ 0 件の読み分け
    // ------------------------------------------------------------------

    @Test
    fun `数えている最中は件数を出さない`() {
        assertEquals("探している", label(searching = true, count = 0))
    }

    @Test
    fun `数え終わって 0 件なら当たらないと出す`() {
        assertEquals("当たらない", label(searching = false, count = 0))
    }

    @Test
    fun `探している最中は当たらないと書かない`() {
        // 同じ「count = 0」が2つの意味を持つので、**文字が違うこと**まで見る。
        assertNotEquals(label(searching = false, count = 0), label(searching = true, count = 0))
    }

    @Test
    fun `数えている最中は件数が届いていても出さない`() {
        // 前の検索の件数が残っていても、それは今の語の答えではない。
        assertEquals("探している", label(searching = true, count = 12, index = 3))
    }

    // ------------------------------------------------------------------
    // 条件が無い / 受け付けなかった
    // ------------------------------------------------------------------

    @Test
    fun `条件が無ければ何も出さない`() {
        assertEquals("", label(hasQuery = false))
    }

    @Test
    fun `受け付けなかった理由は件数より先に出す`() {
        assertEquals(
            "正規表現が読めない: Unclosed group",
            label(error = "正規表現が読めない: Unclosed group", hasQuery = true, count = 5, index = 2)
        )
    }

    @Test
    fun `受け付けなかった理由は探している最中より先に出す`() {
        assertEquals("壊れている", label(error = "壊れている", searching = true))
    }

    // ------------------------------------------------------------------
    // 件数
    // ------------------------------------------------------------------

    @Test
    fun `乗っている一致があれば何件目かを出す`() {
        assertEquals("3 / 17", label(count = 17, index = 3))
    }

    @Test
    fun `どれにも乗っていなければ件数だけ出す`() {
        assertEquals("17 件", label(count = 17, index = 0))
    }

    // ------------------------------------------------------------------
    // 条件（引き直しの判断材料）
    // ------------------------------------------------------------------

    @Test
    fun `語が同じでもフラグが違えば別の条件`() {
        val plain = SearchQuery("todo", false, false, false)
        assertNotEquals(plain, SearchQuery("todo", true, false, false))
        assertNotEquals(plain, SearchQuery("todo", false, true, false))
        assertNotEquals(plain, SearchQuery("todo", false, false, true))
    }

    @Test
    fun `同じ条件は同じものとして扱う`() {
        // **同じ条件を投げ直さない**ための根拠。投げ直すと数え直しになり、
        // その間だけ件数が 0 に見える（＝「当たらない」が一瞬出る）。
        assertEquals(SearchQuery("todo", true, false, true), SearchQuery("todo", true, false, true))
        assertEquals(
            SearchQuery("todo", true, false, true).hashCode(),
            SearchQuery("todo", true, false, true).hashCode()
        )
    }

    @Test
    fun `空の語は引ける条件ではない`() {
        assertTrue(SearchQuery("", false, false, false).isEmpty())
        assertFalse(SearchQuery("a", false, false, false).isEmpty())
        // null を渡しても落ちない（欄が空のまま組み立てられることがある）。
        assertTrue(SearchQuery(null, false, false, false).isEmpty())
    }

    // ------------------------------------------------------------------
    // 置き換えてよいか（2026-10-02 レビューの R4）
    // ------------------------------------------------------------------

    @Test
    fun `読めない条件が出ている間は、前の条件が残っていても置き換えない`() {
        // エンジンは壊れた式を断ったあとも前に通った条件を持つ（hasQuery = true のまま）。
        assertFalse(SearchStatus.canReplace(error = "正規表現が読めない", searching = false, hasQuery = true))
    }

    @Test
    fun `数えている最中と条件が無いときは置き換えない`() {
        assertFalse(SearchStatus.canReplace(error = null, searching = true, hasQuery = true))
        assertFalse(SearchStatus.canReplace(error = null, searching = false, hasQuery = false))
    }

    @Test
    fun `通った条件を数え終えたら置き換えられる`() {
        assertTrue(SearchStatus.canReplace(error = null, searching = false, hasQuery = true))
    }

    /**
     * 帯が見る状態の移り変わりを、エンジンの順番どおりに並べて踏む。
     *
     * 順番は Sora 0.24.6 の `EditorSearcher.search` の bytecode で確かめたもの ──
     * `Pattern.compile` が `currentPattern` の代入より先にあり、式が読めなければ条件は前のまま残る。
     * 実物のエンジンはこの JVM 試験では作れない（`CodeEditor` が Android を要る）ので、
     * その順番だけを写した偽物で見る。実画面での確認は実機の受入に残す。
     */
    @Test
    fun `有効な検索から壊れた式に変えると置き換えが止まり、直すと戻る`() {
        val engine = RegexEngineOrder()
        engine.start("foo")
        assertTrue(engine.canReplace())
        engine.start("foo(")
        assertEquals("前の条件が残る（エンジンの作法）", "foo", engine.pattern)
        assertFalse("壊れた式の間に前の条件で置き換えられる", engine.canReplace())
        engine.start("foo(o)")
        assertTrue("直した式で置き換えが戻らない", engine.canReplace())
    }
}

/**
 * Sora 0.24.6 の `EditorSearcher.search` の順番だけを写した偽物（[SearchStatusTest] の R4 で使う）。
 * 式を読んでから条件を差し替えるので、読めなければ前の条件が残る。
 */
private class RegexEngineOrder {
    var pattern: String? = null
    var error: String? = null
    fun start(regex: String) {
        try {
            java.util.regex.Pattern.compile(regex)
        } catch (e: java.util.regex.PatternSyntaxException) {
            error = "正規表現が読めない"
            return
        }
        pattern = regex
        error = null
    }
    fun canReplace() = SearchStatus.canReplace(error, searching = false, hasQuery = pattern != null)
}
