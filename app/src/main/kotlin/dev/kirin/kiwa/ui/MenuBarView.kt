package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import dev.kirin.kiwa.command.Command
import dev.kirin.kiwa.command.Commands

/**
 * メニューバー。コマンドを分類ごとにまとめて表示する。
 *
 * ## 操作を見つけやすくする
 *
 * 操作名をメニューに並べ、入口を見つけやすくする。「名前を付けて保存」や「言語を選ぶ」も
 * ⋯ の中に隠さず、分類ごとの一覧から開ける。
 *
 * ここは**表（`Commands.kt`）の分類をそのままメニューにする** ── 表に足したコマンドは
 * 必ずどれかのメニューに出るので、⋯ に入れ忘れる・バーに載せ忘れる、が起きない。
 * 保存など一部の操作はメニューを開く分だけ2手になる。記号キー列には保存が残る。
 *
 * ## 今できないもの
 *
 * 操作できない項目は**薄くして押せなくする。消して詰めない**
 * （並びを保ち、項目を見失わないようにする）。
 */
class MenuBarView(
    context: Context,
    private val commands: () -> Commands,
    private val onPalette: () -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private var palette: Palette = Palette.SUMI
    private val menuButtons = ArrayList<TextView>()
    private var open: PopupWindow? = null

    private val paletteField = TextView(context).apply {
        text = "⌘  何をする？　ファイル / コマンド / 行"
        textSize = 13f
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        isFocusable = true
        contentDescription = "コマンドパレット"
        setOnClickListener { onPalette() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(3), dp(6), dp(3))
    }

    /** メニューを並べ直す。**表から組む**ので、表が変われば出るものも変わる。 */
    fun rebuild() {
        removeAllViews()
        menuButtons.clear()
        for ((group, items) in commands().menus()) {
            val button = TextView(context).apply {
                text = group.label
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { showMenu(this, items) }
            }
            menuButtons.add(button)
            addView(button, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        addView(View(context), LayoutParams(0, 1, 1f))
        addView(paletteField, LayoutParams(dp(360), dp(32)).apply { marginStart = dp(8) })
        apply(palette)
    }

    fun apply(palette: Palette) {
        this.palette = palette
        setBackgroundColor(palette.toolbar)
        for (button in menuButtons) button.setTextColor(palette.text)
        paletteField.background = GradientDrawable().apply {
            setColor(palette.background)
            setStroke(dp(1), palette.frame)
        }
        paletteField.setTextColor(palette.dim)
    }

    private fun showMenu(anchor: View, items: List<Command>) {
        open?.dismiss()
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val popup = PopupWindow(context)
        for (command in items) list.addView(row(command) { popup.dismiss(); command.run() })
        val scroll = ScrollView(context).apply { addView(list) }
        popup.contentView = scroll
        popup.width = dp(340)
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        // **キーで上下できるように focusable にする。** 押せる行だけが止まる。
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(GradientDrawable().apply {
            setColor(palette.toolbar)
            setStroke(dp(1), palette.frame)
        })
        popup.elevation = 8 * density
        popup.setOnDismissListener { if (open === popup) open = null }
        open = popup
        popup.showAsDropDown(anchor, 0, dp(2))
    }

    private fun row(command: Command, onPick: () -> Unit): View {
        val available = command.available
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            isEnabled = available
            isClickable = available
            isFocusable = available
            alpha = if (available) 1f else DIMMED
            contentDescription = command.label
            setOnClickListener { onPick() }
            setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) palette.button else 0) }
        }
        row.addView(TextView(context).apply {
            text = command.label
            textSize = 15f
            setTextColor(palette.text)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val detail = command.detail
        if (detail.isNotEmpty()) {
            row.addView(TextView(context).apply {
                text = detail
                textSize = 12f
                maxLines = 1
                setTextColor(palette.dim)
                setPadding(dp(12), 0, 0, 0)
            })
        }
        return row
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val DIMMED = SymbolRowView.DIMMED
    }
}
