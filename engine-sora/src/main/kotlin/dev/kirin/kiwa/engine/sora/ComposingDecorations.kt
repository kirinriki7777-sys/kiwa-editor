package dev.kirin.kiwa.engine.sora

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import dev.kirin.editoradapter.ComposingDecoration

/**
 * IME が渡してきた未確定文字列から、装飾の区間を取り出す。
 *
 * 日本語 IME は「変換対象の文節」と「それ以外」を塗り分けて渡してくる。実測では Gboard が
 * [BackgroundColorSpan] の alpha `0x66` と `0x19` で塗り分けており、その情報は
 * `setComposingText` の `CharSequence` に付いた状態で入力先まで届いていた。
 * Sora は `text.toString()` で捨てるので（widget/EditorInputConnection.java:448）、
 * **捨てられる前にここで取り出す**。
 *
 * 下線は取らない。Sora が composing の下線を自前で描いており
 * （widget/EditorRenderer.java:1516）、背景（同:1286）とは別処理で共存するため。
 */
internal object ComposingDecorations {

    /**
     * @param text   IME が渡してきた未確定文字列
     * @param offset この文字列がバッファのどこから始まるか（絶対インデックス）
     */
    fun extract(text: CharSequence, offset: Int): List<ComposingDecoration> {
        if (text !is Spanned) return emptyList()
        val spans = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
        if (spans.isEmpty()) return emptyList()
        val out = ArrayList<ComposingDecoration>(spans.size)
        for (span in spans) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            out.add(
                ComposingDecoration(
                    offset + start,
                    offset + end,
                    span.backgroundColor,
                    ComposingDecoration.COLOR_UNSPECIFIED
                )
            )
        }
        return out
    }
}
