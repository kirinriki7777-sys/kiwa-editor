package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 見出しを枠の上の線に載せた領域（見た目案 01 の `┌ エクスプローラー ─ × ┐`）。
 *
 * ## なぜ枠で囲むか
 *
 * 面の明るさの差だけで区切ると、差が小さい配色では領域が分かりにくい。
 * **「ここは領域である」という考え方**を枠と見出しで示す。
 * 枠と見出しがあれば、配色が変わっても「どこからどこまでが何か」が読める。
 *
 * ## 描き方
 *
 * 枠と見出しはこのビューが自分で描く（子ではない）。見出しの後ろだけ地の色で塗って
 * 線を切るので、見出しが線の上に載って見える。中身は1つだけ [setContent] で入れ、
 * 見出しの高さのぶん下げて置く ── 見出しに中身が重ならない。
 *
 * 閉じる × だけは**押せる子**として置く（描いただけだと押せない）。
 */
class TitledFrame(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val titleHeight = (22 * density).toInt()
    private val inset = (1 * density).toInt().coerceAtLeast(1)

    private var title: String = ""
    private var content: View? = null

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = density.coerceAtLeast(1f)
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        typeface = Typeface.MONOSPACE
    }

    private val close = TextView(context).apply {
        text = "×"
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        contentDescription = "閉じる"
        visibility = GONE
    }

    init {
        setWillNotDraw(false)
        addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, titleHeight, Gravity.TOP or Gravity.END).apply {
            marginEnd = (10 * density).toInt()
        })
    }

    fun setTitle(text: String) {
        title = text
        invalidate()
    }

    /** × を出す。null なら出さない。 */
    fun setOnClose(onClose: (() -> Unit)?) {
        close.visibility = if (onClose == null) GONE else VISIBLE
        close.setOnClickListener { onClose?.invoke() }
    }

    /** 中身を入れる。**見出しの下・枠の内側**に収まるよう余白を付ける。 */
    fun setContent(view: View) {
        content?.let { removeView(it) }
        content = view
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = titleHeight
            leftMargin = inset
            rightMargin = inset
            bottomMargin = inset
        })
    }

    /**
     * 色を入れる。[emphasized] の領域は枠と見出しを強調の色にする ──
     * 01 の「今いる領域」を見せる部分。
     */
    fun apply(palette: Palette, emphasized: Boolean) {
        borderPaint.color = if (emphasized) palette.accent else palette.frame
        titlePaint.color = if (emphasized) palette.accent else palette.dim
        fillPaint.color = palette.background
        close.setTextColor(palette.dim)
        close.setBackgroundColor(palette.background)
        setBackgroundColor(palette.background)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = borderPaint.strokeWidth / 2
        val top = titleHeight / 2f
        canvas.drawRect(half, top, width - half, height - half, borderPaint)
        if (title.isEmpty()) return
        // 見出しの後ろだけ線を切る。左右に少し余白を取ると、線と字がくっつかない。
        val pad = 6 * density
        val left = 10 * density
        val textWidth = titlePaint.measureText(title)
        canvas.drawRect(left, 0f, left + textWidth + pad * 2, titleHeight.toFloat(), fillPaint)
        val metrics = titlePaint.fontMetrics
        val baseline = titleHeight / 2f - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(title, left + pad, baseline, titlePaint)
    }
}
