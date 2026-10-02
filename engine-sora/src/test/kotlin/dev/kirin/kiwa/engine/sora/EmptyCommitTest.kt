package dev.kirin.kiwa.engine.sora

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中身の無い確定を塞ぐ条件。
 *
 * **塞ぎすぎると入力が壊れる**ので、意味を持つ空文字（composing 中 / 選択中）は必ず通す。
 */
class EmptyCommitTest {

    @Test
    fun `composing も選択も無い空文字は何もしない`() {
        assertTrue(EmptyCommit.isNoOp("", composing = false, selected = false))
        assertTrue(EmptyCommit.isNoOp(null, composing = false, selected = false))
    }

    @Test
    fun `composing 中の空文字は未確定を消す意味があるので通す`() {
        assertFalse(EmptyCommit.isNoOp("", composing = true, selected = false))
    }

    @Test
    fun `選択中の空文字は選択を消す意味があるので通す`() {
        assertFalse(EmptyCommit.isNoOp("", composing = false, selected = true))
    }

    @Test
    fun `中身のある確定は当然通す`() {
        assertFalse(EmptyCommit.isNoOp("日本語", composing = false, selected = false))
        assertFalse(EmptyCommit.isNoOp(" ", composing = false, selected = false))
    }
}
