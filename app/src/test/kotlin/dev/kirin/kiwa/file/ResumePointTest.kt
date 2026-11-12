package dev.kirin.kiwa.file

import dev.kirin.kiwa.view.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 起動したら続きから（[ResumePoint]）。 */
class ResumePointTest {

    @Test
    fun `位置はファイルの長さに収める`() {
        val point = ResumePoint("/a/b.txt", 100, ViewMode.WORDPROCESSOR)
        assertEquals(10, point.offsetIn(10))
        assertEquals(0, point.offsetIn(0))
        assertEquals(100, point.offsetIn(500))
        assertEquals(0, ResumePoint("/a/b.txt", -3, ViewMode.CODE).offsetIn(10))
    }

    @Test
    fun `保存してあった3つから戻す`() {
        assertEquals(
            ResumePoint("/a/b.txt", 7, ViewMode.WORDPROCESSOR),
            ResumePoint.restore("/a/b.txt", 7, "WORDPROCESSOR")
        )
        assertEquals(ResumePoint("/a/b.txt", 0, ViewMode.CODE), ResumePoint.restore("/a/b.txt", 0, "CODE"))
    }

    @Test
    fun `T5_縦の記録の値を保存して復元できる`() {
        val saved = ResumePoint("/a/b.txt", 7, ViewMode.WORDPROCESSOR, true)
        assertEquals(saved, ResumePoint.restore(saved.path, saved.offset, saved.mode.name, saved.vertical))
    }

    @Test
    fun `T5_縦の欄の無い旧版は横でコードの記録も横`() {
        assertEquals(false, ResumePoint.restore("/a/b.txt", 7, "WORDPROCESSOR")!!.vertical)
        assertEquals(false, ResumePoint.restore("/a/b.kt", 7, "CODE", true)!!.vertical)
        assertEquals(false, ResumePoint.restore("/a/b.txt", 7, "WORDPROCESSOR", false)!!.vertical)
    }

    @Test
    fun `足りない・読めない記録は無いものとする`() {
        assertNull(ResumePoint.restore(null, 0, "CODE"))
        assertNull(ResumePoint.restore("", 0, "CODE"))
        assertNull(ResumePoint.restore("/a/b.txt", 0, null))
        assertNull(ResumePoint.restore("/a/b.txt", 0, "VERTICAL"))
    }
}
