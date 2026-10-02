package dev.kirin.kiwa.engine.sora

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan

/**
 * 境界で起きたことを外へ流す口。**実装は `:app` 側が持つ**（ここは書き出さない）。
 *
 * ## なぜエンジン側に置くか
 *
 * 観測したいのは IME と Sora の間で起きることで、その現場は
 * [ComposingBoundaryConnection] ── つまりこのモジュールの中にある。
 * アプリからは `CodeEditor` の `InputConnection` を掴めないので、
 * アプリ側だけに計測層を置く形は成立しない。
 *
 * ## JSONL の名前の契約
 *
 * `source` / `event` / `phase` / `call` の名前はログ形式の一部。
 * `tools/analyze-composing-paths.py` がこれらを使って呼び出しと編集内容を対応付けるため、
 * 名前を変えると解析結果から該当行が落ちる。
 *
 * ## IME の同期呼び出しの中に居ることを忘れない
 *
 * 実装側は **JSON 化もファイル書き込みもこのスレッドでやってはいけない**。
 * 1イベントごとに flush すると、打鍵のたびにディスクとレイアウトの仕事が乗り、
 * 測ること自体が測る対象を変える。
 */
interface ImeTrace {

    /** enter と exit を対にするための単調増加 id。呼んだスレッドで採番する。 */
    fun nextCall(): Long

    /**
     * @param fields キーと値が交互に並ぶ。値は文字列・数値・真偽値・null のみ。
     */
    fun event(source: String, event: String, vararg fields: Any?)
}

/** 計測ログに載せる値を作る。 */
internal object TraceText {

    fun preview(text: CharSequence?): String? {
        if (text == null) return null
        val s = text.toString()
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return if (s.length <= 200) s else s.substring(0, 200) + "…(${s.length})"
    }

    /**
     * IME が付けたスパンの一覧。**症状Bの現場そのもの** ──
     * ここに `BackgroundColorSpan` が出ているのに画面に色が乗らないなら、境界が受け損ねている。
     */
    fun spans(text: CharSequence?): String? {
        if (text !is Spanned) return null
        val all = text.getSpans(0, text.length, Any::class.java)
        if (all.isEmpty()) return ""
        return all.joinToString(" ") { span ->
            val range = "[${text.getSpanStart(span)},${text.getSpanEnd(span)}]"
            val detail = when (span) {
                is BackgroundColorSpan -> "#" + Integer.toHexString(span.backgroundColor)
                is ForegroundColorSpan -> "fg#" + Integer.toHexString(span.foregroundColor)
                is UnderlineSpan -> "underline"
                else -> ""
            }
            span.javaClass.simpleName + range + detail
        }
    }
}
