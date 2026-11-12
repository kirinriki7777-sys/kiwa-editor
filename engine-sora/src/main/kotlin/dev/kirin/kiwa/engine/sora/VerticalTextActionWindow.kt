package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.widget.component.EditorTextActionWindow

/** ボタン・購読は Sora のまま。全表示経路が通る位置決めと、縦の操作中の表示だけを替える。 */
internal class VerticalTextActionWindow(private val kiwa: KiwaCodeEditor) : EditorTextActionWindow(kiwa) {
    private val normalHeight = height

    override fun displayWindow() {
        if (kiwa.isVertical && (!kiwa.cursor.isSelected || kiwa.verticalActionsBlocked)) {
            dismiss()
            return
        }
        val fittedHeight = if (kiwa.isVertical) normalHeight.coerceAtMost(kiwa.height) else normalHeight
        if (height != fittedHeight) setSize(width, fittedHeight)
        super.displayWindow()
    }

    override fun setLocationAbsolutely(x: Int, y: Int) {
        if (!kiwa.isVertical) {
            super.setLocationAbsolutely(x, y)
            return
        }
        setSize(width.coerceAtMost(kiwa.width), height)
        val position = kiwa.verticalTextActionPosition(width, height)
        // EditorPopupWindow は View 左上が基準。offsetX/Y（横のスクロール）をこの口が打ち消す。
        super.setLocationAbsolutely(position.x.toInt(), position.y.toInt())
    }

    override fun show() {
        if (kiwa.isVertical && (!kiwa.cursor.isSelected || kiwa.verticalActionsBlocked)) return
        super.show()
    }
}
