package dev.kirin.kiwa.engine.sora

/**
 * 縦書きエディタでのキーボード選択制御。
 *
 * Sora の `cursor` は常に `left <= right` で anchor（選択開始点）と focus（動いている端）の
 * 区別を失うため、Shift+矢印キーの連続押下、逆方向への縮小、反転（anchor跨ぎ）を追跡する。
 */
internal class VerticalSelectionController {

    var anchor: Int? = null
        private set

    /**
     * 矢印キー移動時の新しい選択範囲 (left, right) を計算する。
     *
     * @param currentLeft 現在の選択左端 (cursor.left)
     * @param currentRight 現在の選択右端 (cursor.right)
     * @param isShiftPressed Shiftが押されているか
     * @param moveFocus フォーカス位置を1ステップ移動させる関数
     * @return 新しい選択範囲 Pair(start, end) where start <= end
     */
    fun computeSelection(
        currentLeft: Int,
        currentRight: Int,
        isShiftPressed: Boolean,
        moveFocus: (currentFocus: Int) -> Int
    ): Pair<Int, Int> {
        if (!isShiftPressed) {
            anchor = null
            val base = currentLeft
            val newPos = moveFocus(base)
            return Pair(newPos, newPos)
        }

        // 起点は選択のどちらかの端にしか無い。IME などが選択を動かした後に残った起点は捨てる。
        val currentAnchor = anchor?.takeIf { it == currentLeft || it == currentRight }
            ?: currentLeft.also { anchor = it }
        val focus = if (currentAnchor == currentLeft) currentRight else currentLeft

        val newFocus = moveFocus(focus)
        val newLeft = minOf(currentAnchor, newFocus)
        val newRight = maxOf(currentAnchor, newFocus)
        return Pair(newLeft, newRight)
    }

    fun reset() {
        anchor = null
    }
}
