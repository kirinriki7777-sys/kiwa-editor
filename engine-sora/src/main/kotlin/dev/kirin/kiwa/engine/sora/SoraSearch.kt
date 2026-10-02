package dev.kirin.kiwa.engine.sora

import dev.kirin.editoradapter.EditorSearch
import dev.kirin.editoradapter.SearchQuery
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import java.util.regex.PatternSyntaxException

/**
 * [EditorSearch] の Sora 実装。**Sora の `EditorSearcher` をそのまま包む**（自作しない）。
 *
 * 公開されているのは正規表現・大文字小文字・単語単位・1件/全件置換・次へ/前へ・一致件数まで。
 * Sora の型が出るのは `SearchOptions` / `ReplaceOptions` の2つだけなので、
 * ここで受けて bool から組み立てれば `:app` へは1つも出ない。
 *
 * ## ここが足しているのは1つ ── 「探している最中」
 *
 * `EditorSearcher` は検索を別スレッドで回し（`EditorSearcher.SearchRunnable`）、
 * **数え終わるまで `getMatchedPositionCount()` は 0 を返す**（`isResultValid()` が false）。
 * つまり **「まだ数えている」と「1件も無い」が同じ 0 として出てくる**。
 * その `isResultValid()` は protected で外から呼べないので、
 * **こちらで印を持つ** ── [start] で立て、`PublishSearchResultEvent` が来たら倒す。
 *
 * 検索は 0 件が正常値になる機能なので、ここを混ぜると
 * 「当たらない」と出ているものの意味が読めなくなる（E10b で同じ形を踏んだ）。
 */
internal class SoraSearch(private val editor: CodeEditor) : EditorSearch {

    private val searcher: EditorSearcher get() = editor.searcher

    private var listener: Runnable? = null

    /** 数え終わっていない間だけ true。**この印がこのクラスの存在理由。** */
    private var searching = false

    private var lastError: String? = null

    init {
        // **`stopSearch()` でも飛んでくる**（Sora の Javadoc に明記）ので、
        // 「終わった」の合図としてはどちらでも正しい ── 呼ぶ側は件数を聞き直すだけ。
        editor.subscribeAlways(PublishSearchResultEvent::class.java) {
            searching = false
            // ★**この場で知らせない。1フレーム逃がす。**
            //
            // Sora はイベントを配ってから後始末をする（`EditorSearcher.java:617-618`）:
            //
            //     editor.dispatchEvent(new PublishSearchResultEvent(editor));
            //     currentThread = null;
            //
            // 受け取った側がその場で検索し直すと、`executeMatch()` が立てた**新しい**
            // スレッドの参照を、戻ってきた 618 行が null で潰す。すると次の完走時に
            // `currentThread == localThread` が偽になり、**結果イベントが二度と出ない**。
            //
            // 実機で踏んだ（2026-09-07）── 1件置換のあと件数が「探している」で固まった。
            // ハイライトは正しく出ているので、**嘘をつくのは状態表示だけ**という
            // 見つけにくい壊れ方をする。
            editor.post { listener?.run() }
        }
        // 末尾まで行ったら先頭へ回る。「次へ」を押し続けて止まると、
        // 1件も無いのか回り切ったのかが指では区別できない。
        searcher.isCyclicJumping = true
    }

    override fun start(query: SearchQuery): Boolean {
        if (query.isEmpty()) {
            // 欄を空にしたら結果ごと消す ── 語が無いのにハイライトが残る方が分からない。
            stop()
            lastError = null
            return false
        }
        return try {
            searcher.search(query.pattern(), optionsOf(query))
            searching = true
            lastError = null
            true
        } catch (e: PatternSyntaxException) {
            // **前の結果はそのまま残す。** 正規表現は打っている途中が壊れているのが普通で
            // （`(` まで打った時点など）、そのたびにハイライトが消えると打てない。
            lastError = "正規表現が読めない: ${e.description ?: e.message}"
            false
        } catch (e: IllegalArgumentException) {
            lastError = e.message ?: "検索できない語"
            false
        }
    }

    override fun stop() {
        searching = false
        // hasQuery が false でも安全（`stopSearch` は状態を見ない）。
        searcher.stopSearch()
    }

    override fun hasQuery(): Boolean = searcher.hasQuery()

    override fun isSearching(): Boolean = searching

    override fun matchCount(): Int {
        // **条件が無いときに聞くと落ちる**（`checkState()` が IllegalStateException）。
        if (!searcher.hasQuery() || searching) return 0
        return searcher.matchedPositionCount
    }

    override fun currentIndex(): Int {
        if (!searcher.hasQuery() || searching) return 0
        // Sora は 0 始まりで、乗っていなければ -1。画面には「3 / 17」と出すので 1 始まりへ直す。
        val index = searcher.currentMatchedPositionIndex
        return if (index < 0) 0 else index + 1
    }

    override fun error(): String? = lastError

    override fun next(): Boolean {
        if (!searcher.hasQuery()) return false
        // 数え終わっていなければ Sora 側が false を返す（`isResultValid()`）。
        return searcher.gotoNext()
    }

    override fun previous(): Boolean {
        if (!searcher.hasQuery()) return false
        return searcher.gotoPrevious()
    }

    override fun replaceCurrent(replacement: String) {
        if (!searcher.hasQuery() || lastError != null) return
        searcher.replaceCurrentMatch(replacement)
    }

    override fun replaceAll(replacement: String, whenDone: Runnable?) {
        // **読めない条件が出ている間は置き換えない。** Sora は新しい式を `Pattern.compile` してから
        // 条件を差し替えるので（0.24.6 の `EditorSearcher.search`）、壊れた式を渡したあとも
        // 前に通った条件を持ったまま ── ここで通すと、画面の条件と違う所を書き換える。
        if (!searcher.hasQuery() || lastError != null) {
            whenDone?.run()
            return
        }
        // **Sora が自前の ProgressDialog を出して塞ぐ**（`EditorSearcher.replaceAll`）。
        // 意匠はこちらの持ち物ではないが、全件置換は元に戻すのが高くつく操作なので
        // 進んでいることが見えるのはむしろ都合がいい。
        //
        // **失敗したときは [whenDone] が呼ばれない**（Sora は Toast を出して終わる）。
        // 呼ぶ側は「呼ばれたら引き直す」だけを当てにすること。
        searcher.replaceAll(replacement, whenDone)
    }

    override fun setOnResultListener(listener: Runnable?) {
        this.listener = listener
    }

    /**
     * Sora の検索条件を組み立てる。
     *
     * **単語単位と正規表現は同時に選べない**（`SearchOptions.type` が3択）。
     * [SearchQuery] の Javadoc どおり正規表現を勝たせる。
     *
     * **★単語単位は日本語に効かない** ── Sora は `\b` で挟んだ正規表現に直すので
     * （`EditorSearcher.SearchRunnable`）、単語の境界を持たない日本語では当たらなくなる。
     * 英数の識別子を探すための道具として出す。
     */
    private fun optionsOf(query: SearchQuery): EditorSearcher.SearchOptions {
        val type = when {
            query.regex() -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            query.wholeWord() -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
            else -> EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        // Sora が持つのは「区別しない」の側。こちらは「区別する」で持っているので反転する。
        return EditorSearcher.SearchOptions(type, !query.caseSensitive())
    }
}
