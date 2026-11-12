package dev.kirin.kiwa.engine.sora

/** Sora が失う固定端を保持し、指の位置をマスの境界へ直す。重なった時は直前の非空の選択を保つ。 */
internal class VerticalTouchSelection(start: Boolean, left: Int, right: Int) {
    val anchor = if (start) right else left
    private var focus = if (start) left else right

    fun drag(layout: VerticalLayout, x: Float, y: Float, scrollX: Float): Pair<Int, Int> {
        val offset = layout.offsetAt(x, y, scrollX)
        if (offset != anchor) focus = offset
        return minOf(anchor, focus) to maxOf(anchor, focus)
    }
}
