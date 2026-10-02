package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.color.ResolvableColor
import io.github.rosemoe.sora.lang.styling.span.SpanColorResolver
import io.github.rosemoe.sora.lang.styling.span.SpanExtAttrs
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 変換中の装飾を、**構文ハイライトと同じスパンの経路**で描くための重ね。
 *
 * ## なぜ経路を移したか
 *
 * 最初は `CodeEditor.setHighlightTexts()` を使っていた（E2）。実機では出たが、
 * **表示行が1行だけのとき描かれない**。原因は Sora 0.24.6 の描画側にある ──
 * `EditorRenderer.drawRows` の Step 1 が行を2周し、1周目（widget/EditorRenderer.java:1203）が
 * `lastPreparedLine` を進めてしまうので、2周目（同:1263）の `computeHighlightPositions` が
 * **同じ行では呼ばれない**。可視行が全部同じ行＝表示行1行のとき、これは必ず起きる。
 * 実測は 1行だけなら 0 画素 / 3行なら 1,227 画素。
 *
 * **表示行が1行なのは「空のファイルを開いて打ち始める」場面そのもの**なので、無視できない。
 *
 * スパンの背景は `TextRow.drawSpan`（graphics/TextRow.java:828）が
 * **文字を描く経路の中で**塗るので、`lastPreparedLine` の共有とは無関係に動く。
 * だから装飾をそちらへ載せ替える。
 *
 * ## 何を捨てたか
 *
 * **枠（border）は描かなくなった。** スパンの経路には枠が無い。
 * 実測した IME（Gboard / Sumire）はどちらも `BackgroundColorSpan` しか渡してこないので
 * 今のところ失うものは無いが、渡してくる IME があれば無視される。
 *
 * ## 持ち方
 *
 * **エンジンのバッファには書き込まない。** Sora の `Content` は char ベースで
 * スパンを保持できない（text/ContentLine.java:40）。ここが持つのは行・桁の範囲だけで、
 * 解析器が作ったスパンには触らず、**読み出しのときに重ねる**（[ComposingOverlaySpans]）。
 */
internal class ComposingOverlay {

    /**
     * 変換中の装飾1区間。1行に収まる形で持つ（複数行にまたがる装飾は行ごとに割ってから入れる）。
     *
     * @param endColumn 排他。行末までなら行の桁数と同じ値になる
     */
    class Range(
        val line: Int,
        val startColumn: Int,
        val endColumn: Int,
        backgroundColor: Int
    ) {
        private val color = backgroundColor

        /**
         * スパンに差し込む解決器。**範囲ごとに1つ作って使い回す** ──
         * 重ねは描画のたびに組み直されるので、ここで作り直すと毎フレーム捨てることになる。
         */
        val resolver: SpanColorResolver = BackgroundResolver(backgroundColor, null)

        /** 元のスパンが自前の解決器を持っていたとき用。前景をそちらへ委ねる。 */
        fun resolverOver(inner: SpanColorResolver): SpanColorResolver = BackgroundResolver(color, inner)

        override fun toString(): String = "$line:$startColumn-$endColumn"
    }

    /** 今の重ね。空なら何も起きない（構文スパンがそのまま出る）。 */
    var ranges: List<Range> = emptyList()
        private set

    val isEmpty: Boolean get() = ranges.isEmpty()

    fun set(ranges: List<Range>) {
        this.ranges = ranges
    }

    fun clear() {
        ranges = emptyList()
    }

    fun rangesOn(line: Int): List<Range> {
        if (ranges.isEmpty()) return emptyList()
        return ranges.filter { it.line == line && it.startColumn < it.endColumn }
    }

    /**
     * 背景だけを固定色で上書きし、前景は握らない。
     *
     * 前景を握らないのは、構文ハイライトの文字色をそのまま生かすため。
     * `null` を返すと呼び出し側（util/RendererUtils.kt:58）が色 ID からの解決へ落ちる。
     *
     * **[inner] をスパンから引き直してはいけない。** 差し込んだ先のスパンから
     * 拡張を読むと自分自身が返ってきて無限再帰になる。持つのは
     * 「差し込む前にそのスパンが持っていた解決器」で、作るときに渡す。
     */
    private class BackgroundResolver(
        background: Int,
        private val inner: SpanColorResolver?
    ) : SpanColorResolver {

        private val background = ConstColor(background)

        override fun getForegroundColor(span: Span): ResolvableColor? = inner?.getForegroundColor(span)

        override fun getBackgroundColor(span: Span): ResolvableColor = background
    }

    companion object {

        /**
         * 1行分のスパン列に重ねを適用した新しい列を返す。重ねが無ければ [base] をそのまま返す。
         *
         * スパンは「開始桁」しか持たず、終わりは次のスパンの開始桁（無ければ行末）で決まる。
         * だから重ねるには**切れ目を入れ直す**しかない ── 元のスパンの開始桁と、
         * 重ねの開始・終了桁を合わせた集合が、新しいスパンの開始桁になる。
         *
         * @param base       解析器が作ったスパン列。開始桁の昇順で、先頭は桁 0
         * @param lineLength その行の桁数。行末ちょうどで終わる重ねが
         *                   **桁数と同じ位置に空のスパンを作らない**ように要る
         */
        @JvmStatic
        fun merge(base: List<Span>, ranges: List<Range>, lineLength: Int): List<Span> {
            if (ranges.isEmpty()) return base

            val source = if (base.isEmpty()) listOf(defaultSpan()) else base

            val cuts = sortedSetOf(0)
            for (span in source) {
                if (span.column in 1 until lineLength) cuts.add(span.column)
            }
            for (range in ranges) {
                if (range.startColumn in 1 until lineLength) cuts.add(range.startColumn)
                if (range.endColumn in 1 until lineLength) cuts.add(range.endColumn)
            }

            val out = ArrayList<Span>(cuts.size)
            for (column in cuts) {
                val origin = originAt(source, column)
                val range = ranges.firstOrNull { column >= it.startColumn && column < it.endColumn }
                out.add(if (range == null) spanAt(origin, column) else decorate(origin, column, range))
            }
            return out
        }

        /** 桁 [column] を含む元のスパン。[source] は開始桁の昇順で先頭が桁 0。 */
        private fun originAt(source: List<Span>, column: Int): Span {
            var found = source[0]
            for (span in source) {
                if (span.column > column) break
                found = span
            }
            return found
        }

        private fun spanAt(origin: Span, column: Int): Span {
            if (origin.column == column) return origin
            return origin.copy().also { it.column = column }
        }

        /**
         * 重ねを持つスパンを作る。
         *
         * **`copy()` ではなく作り直す。** `EmptyReader` が配る既定のスパンは
         * 拡張を持てない実装（`NoExtSpanImpl.setSpanExt` は `UnsupportedOperationException` を投げる）で、
         * 何も入っていない行ではそれが元になる。作り直せばどちらの元でも同じ道を通れる。
         */
        private fun decorate(origin: Span, column: Int, range: Range): Span {
            val span = Span.obtain(column, origin.style)
            origin.underlineColor?.let { span.underlineColor = it }
            val inner = origin.getSpanExt<SpanColorResolver>(SpanExtAttrs.EXT_COLOR_RESOLVER)
            span.setSpanExt(
                SpanExtAttrs.EXT_COLOR_RESOLVER,
                if (inner == null) range.resolver else range.resolverOver(inner)
            )
            return span
        }

        /** 解析器が何も作っていない行のための既定。`EmptyReader` が配るものと同じ形。 */
        private fun defaultSpan(): Span =
            Span.obtain(0, EditorColorScheme.TEXT_NORMAL.toLong())
    }
}
