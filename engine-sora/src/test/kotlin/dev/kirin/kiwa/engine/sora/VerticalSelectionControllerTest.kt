package dev.kirin.kiwa.engine.sora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 縦書きShift選択（anchor と focus の区別）の純JVM回帰試験。
 */
class VerticalSelectionControllerTest {

    @Test
    fun `Shift+UPによる後方拡張_複数回拡張できる`() {
        val controller = VerticalSelectionController()

        // 初期位置 2（非選択 2..2）から Shift+UP で 1 ステップ戻る (2 -> 1)
        val step1 = controller.computeSelection(2, 2, isShiftPressed = true) { it - 1 }
        assertEquals(Pair(1, 2), step1)
        assertEquals(2, controller.anchor)

        // 初版バグでは次回も cursor.right=2 をフォーカスにしたため 1..2 に留まった。
        // 修正後はフォーカスが 1 と判定され、0 へ拡張できる。
        val step2 = controller.computeSelection(step1.first, step1.second, isShiftPressed = true) { it - 1 }
        assertEquals(Pair(0, 2), step2)
        assertEquals(2, controller.anchor)
    }

    @Test
    fun `Shift+DOWNによる縮小と反転`() {
        val controller = VerticalSelectionController()

        // 2 から Shift+UP で 2 回戻って 0..2
        var sel = controller.computeSelection(2, 2, isShiftPressed = true) { it - 1 }
        sel = controller.computeSelection(sel.first, sel.second, isShiftPressed = true) { it - 1 }
        assertEquals(Pair(0, 2), sel)

        // Shift+DOWN で縮小 (0 -> 1)
        sel = controller.computeSelection(sel.first, sel.second, isShiftPressed = true) { it + 1 }
        assertEquals(Pair(1, 2), sel)

        // Shift+DOWN でアンカー位置へ収束 (1 -> 2)
        sel = controller.computeSelection(sel.first, sel.second, isShiftPressed = true) { it + 1 }
        assertEquals(Pair(2, 2), sel)

        // Shift+DOWN でアンカーを跨いで前方へ反転 (2 -> 3)
        sel = controller.computeSelection(sel.first, sel.second, isShiftPressed = true) { it + 1 }
        assertEquals(Pair(2, 3), sel)
        assertEquals(2, controller.anchor)

        // さらに前方へ拡張 (3 -> 4)
        sel = controller.computeSelection(sel.first, sel.second, isShiftPressed = true) { it + 1 }
        assertEquals(Pair(2, 4), sel)
    }

    @Test
    fun `選択が外から動かされた後は古い起点を使わない`() {
        val controller = VerticalSelectionController()
        controller.computeSelection(2, 2, isShiftPressed = true) { it - 1 }
        assertEquals(2, controller.anchor)

        // IME がカーソルを 5 へ動かした（この部品には知らされない）。次の Shift+DOWN は 5 から始まる。
        val sel = controller.computeSelection(5, 5, isShiftPressed = true) { it + 1 }
        assertEquals(Pair(5, 6), sel)
        assertEquals(5, controller.anchor)
    }

    @Test
    fun `Shift無しの矢印キー移動で選択解除される`() {
        val controller = VerticalSelectionController()
        val sel = controller.computeSelection(2, 2, isShiftPressed = true) { it - 1 }
        assertEquals(Pair(1, 2), sel)

        // Shift無しで移動
        val cleared = controller.computeSelection(sel.first, sel.second, isShiftPressed = false) { it + 1 }
        assertEquals(Pair(2, 2), cleared)
        assertNull(controller.anchor)
    }
}
