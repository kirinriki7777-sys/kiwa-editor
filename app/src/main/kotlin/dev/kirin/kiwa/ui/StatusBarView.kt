package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 状態表示（見た目案 01）。**左 = 今の状態、右 = ファイルの属性。**
 *
 * 前は全部を左に一列で並べていた。fresh は左右に分けていて（調査の7点の1つ）、
 * 分けると「今どこにいて何が起きているか」（行・桁・未保存・変換中）と
 * 「このファイルは何か」（文字コード・改行・言語・行数）が別々に読める。
 *
 * 地は強調の色。**本文の下端がどこかが、配色に関係なく1目で分かる。**
 *
 * 右端に文字コードの札（[charset]、2026-10-02）を置く。**押すと `file.encoding` が走る** ──
 * VS Code の状態表示と同じ場所・同じ振る舞い。押せる札なので属性の文字とは別の View にした。
 * 何を走らせるかは [onCharsetClick] で外から渡す（**入口も表を通す**ため、ここは知らない）。
 */
class StatusBarView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    private val left = label().apply { typeface = Typeface.DEFAULT_BOLD }
    private val right = label().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }

    /** 右端の文字コードの札。 */
    private val charset = label().apply {
        // 指で押す札なので、文字の幅より広く当たり判定を取る。
        setPadding(dp(16), dp(3), dp(16), dp(3))
        isClickable = true
        setOnClickListener { onCharsetClick?.invoke() }
    }

    /** 文字コードの札を押した時に走らせるもの。 */
    var onCharsetClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        minimumHeight = dp(26)
        addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(View(context), LayoutParams(0, 1, 1f))
        addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(charset, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
    }

    fun show(state: String, attributes: String) {
        left.text = state
        right.text = attributes
        // 読み上げでは1続きで聞けるように、全体の説明を1つにまとめる。
        contentDescription = "$state · $attributes"
    }

    /** 文字コードの札。**押せない時は薄くして押せなくする**（無題のタブ。`file.encoding` の available と同じ）。 */
    fun showCharset(label: String, enabled: Boolean) {
        charset.text = label
        charset.contentDescription = "文字コード $label"
        charset.isEnabled = enabled
        charset.alpha = if (enabled) 1f else SymbolRowView.DIMMED
    }

    fun apply(palette: Palette) {
        setBackgroundColor(palette.accent)
        left.setTextColor(palette.onAccent)
        right.setTextColor(palette.onAccent)
        // 札は強調の地から浮かせる ── 押せる物だと見て分かるように、選択の地と本文の文字にする。
        charset.setBackgroundColor(palette.button)
        charset.setTextColor(palette.text)
    }

    /** いま出している文字（左・右・文字コードの札を ` · ` でつないだもの）。試験と計測の記録用。 */
    val text: CharSequence get() = "${left.text} · ${right.text} · ${charset.text}"

    private fun label() = TextView(context).apply {
        textSize = 12f
        maxLines = 1
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
