package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.lang.styling.EmptyReader
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.text.CharPosition

/**
 * 解析器が作ったスパンに、変換中の装飾を**読み出しのときだけ**重ねる薄い皮。
 *
 * ## なぜ「皮」なのか
 *
 * 解析器（TextMate）は別のスレッドで走り、同じ [Spans] を書き換え続ける。
 * こちらが装飾を**書き込んで**しまうと、
 *
 *   - 解析が次に走ったとき黙って消える
 *   - 消えなかった場合は、確定した後も残る（元へ戻す手当てが要る）
 *   - 書き込みが解析スレッドとぶつかる
 *
 * の3つを全部相手にすることになる。読み出しのときに重ねるだけなら、
 * **元のスパンは1バイトも変わらない**ので、解析がいつ走ろうと食い違わない。
 *
 * ## 委譲の約束
 *
 * [read] 以外は素通し。特に [modify] と `adjustOnInsert` / `adjustOnDelete` は
 * 解析スレッドとエディタ本体が使う経路なので、**握らずに渡す**。
 * 中身の [base] は null になりうる（言語を付けていない＝解析器が何も作らない場合）。
 *
 * @param lineLength その行の桁数を返す。行末ちょうどで終わる装飾が
 *                   桁数と同じ位置に空のスパンを作らないために要る
 */
internal class ComposingOverlaySpans(
    val base: Spans?,
    private val overlay: ComposingOverlay,
    private val lineLength: (Int) -> Int
) : Spans {

    override fun adjustOnInsert(start: CharPosition, end: CharPosition) {
        base?.adjustOnInsert(start, end)
    }

    override fun adjustOnDelete(start: CharPosition, end: CharPosition) {
        base?.adjustOnDelete(start, end)
    }

    override fun read(): Spans.Reader = OverlayReader(base?.read())

    override fun supportsModify(): Boolean = base?.supportsModify() ?: false

    override fun modify(): Spans.Modifier =
        base?.modify() ?: throw UnsupportedOperationException("no spans to modify")

    override fun getLineCount(): Int = base?.lineCount ?: 0

    private inner class OverlayReader(private val delegate: Spans.Reader?) : Spans.Reader {

        /**
         * 今の行に重ねを適用した結果。重ねが無い行では null で、そのときは [delegate] に素通しする。
         *
         * `moveToLine` の時点で組み立てておくのは、[getSpanCount] と [getSpanAt] が
         * 「今の行」を前提にしているため。
         */
        private var merged: List<Span>? = null

        override fun moveToLine(line: Int) {
            delegate?.moveToLine(line)
            merged = if (line < 0) null else mergedOn(line)
        }

        /**
         * **0 を返してはいけない場面がある。** 描画側は
         * `reader.getSpanCount() == 0` を見て `EmptyReader` へ差し替えるので
         * （widget/EditorRenderer.java:1391）、そこで 0 を返すと重ねごと捨てられる。
         * 解析器が何も作っていない行（言語なし・解析前）でも、重ねがあるなら件数を申告する。
         */
        override fun getSpanCount(): Int = merged?.size ?: delegate?.spanCount ?: 0

        override fun getSpanAt(index: Int): Span {
            val merged = this.merged
            if (merged != null) return merged[index]
            return delegate?.getSpanAt(index) ?: EmptyReader.getInstance().getSpanAt(index)
        }

        override fun getSpansOnLine(line: Int): List<Span> =
            mergedOn(line) ?: delegate?.getSpansOnLine(line) ?: EmptyReader.getInstance().getSpansOnLine(line)

        /** その行に重ねがあるときだけ、重ねたスパン列を作る。無ければ null。 */
        private fun mergedOn(line: Int): List<Span>? {
            if (overlay.isEmpty) return null
            val ranges = overlay.rangesOn(line)
            if (ranges.isEmpty()) return null
            val source = delegate?.getSpansOnLine(line) ?: emptyList()
            return ComposingOverlay.merge(source, ranges, lineLength(line))
        }
    }
}
