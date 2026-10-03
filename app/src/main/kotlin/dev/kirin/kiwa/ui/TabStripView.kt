package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 開いているファイルのタブ列。
 *
 * **横スクロールにしてある。** 常設バー（案C）で横スクロールを禁じたのは
 * 「見えているものを押す」が壊れるからだが、**タブは話が別** ──
 * 開いた本人が何を開いたか知っているので、隠れていても「あるはず」が分かる。
 * バーの方は何が隠れているか知りようがない、という違い。
 *
 * **列の終わりに `+`**（見た目案 01。fresh の調査の7点の1つ）。新しいタブが ⋯ の中にあると2手かかった。
 * 今のタブは上の縁を強調の色にし、未保存の `●` も強調の色で出す。
 */
class TabStripView(
    context: Context,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
    private val onNew: () -> Unit
) : HorizontalScrollView(context) {

    /** タブ1枚に出すもの。**中身（文書）は持たない** ── ここは描くだけ。 */
    class Tab(val title: String, val dirty: Boolean)

    private val density = resources.displayMetrics.density
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun render(tabs: List<Tab>, active: Int, palette: Palette) {
        // 列の下に区切り線。本文との境目が地の色の差だけだと、配色によっては見えない。
        background = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(palette.frame) },
            GradientDrawable().apply { setColor(palette.background) }
        )).apply { setLayerInset(1, 0, 0, 0, dp(1)) }
        row.removeAllViews()
        for ((index, tab) in tabs.withIndex()) {
            row.addView(tabView(tab, index == active, index, palette))
        }
        row.addView(TextView(context).apply {
            text = "+"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            contentDescription = "新しいタブ"
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onNew() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TAB_HEIGHT)))
        // 選んだタブが画面の外にあると「切り替わっていない」ように見える。
        post {
            val view = row.getChildAt(active) ?: return@post
            smoothScrollTo(view.left - 24, 0)
        }
    }

    private fun tabView(tab: Tab, isActive: Boolean, index: Int, palette: Palette): ViewGroup {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(2), 0)
            // 今のタブは上の縁を強調の色で2dp。地は一段明るく（暗く）して、列から浮かせる。
            background = if (isActive) {
                LayerDrawable(arrayOf(
                    GradientDrawable().apply { setColor(palette.accent) },
                    GradientDrawable().apply { setColor(palette.button) }
                )).apply { setLayerInset(1, 0, dp(2), 0, 0) }
            } else {
                null
            }
            // 名前の字の上だけでなく、タブの面のどこを押しても選べるように。
            setOnClickListener { onSelect(index) }
        }
        val label = TextView(context).apply {
            text = tab.title
            textSize = 13f
            maxLines = 1
            setTextColor(if (isActive) palette.text else palette.dim)
            setOnClickListener { onSelect(index) }
        }
        holder.addView(label)
        // **未保存は「●」で出す。** 名前を斜体にする等より、開いている本数が増えたときに読み取りやすい。
        if (tab.dirty) {
            holder.addView(TextView(context).apply {
                text = "●"
                textSize = 11f
                setTextColor(palette.accent)
                setPadding(dp(8), 0, 0, 0)
                contentDescription = "未保存"
                setOnClickListener { onSelect(index) }
            })
        }
        val close = TextView(context).apply {
            text = "×"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(palette.dim)
            contentDescription = "タブを閉じる"
            // 押せる幅は 32dp 以上（案B）。閉じるは**取り返しがつかない側**なので、高さはタブの高さまでに留める。
            minWidth = dp(CLOSE_WIDTH)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClose(index) }
        }
        holder.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TAB_HEIGHT))
        return holder
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        /** タブと `+` の高さ（dp）。 */
        const val TAB_HEIGHT = 44

        /** × の押せる幅（dp）。 */
        const val CLOSE_WIDTH = 32
    }
}
