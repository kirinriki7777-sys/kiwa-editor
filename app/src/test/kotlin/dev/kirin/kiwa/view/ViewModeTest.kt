package dev.kirin.kiwa.view

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewModeTest {

    @Test
    fun txtはワープロ() {
        assertEquals(ViewMode.WORDPROCESSOR, ViewMode.defaultFor("memo.txt"))
        assertEquals(ViewMode.WORDPROCESSOR, ViewMode.defaultFor("MEMO.TXT"))
    }

    @Test
    fun それ以外はコード() {
        assertEquals(ViewMode.CODE, ViewMode.defaultFor("README.md"))
        assertEquals(ViewMode.CODE, ViewMode.defaultFor("Main.kt"))
        assertEquals(ViewMode.CODE, ViewMode.defaultFor("txt"))
        assertEquals(ViewMode.CODE, ViewMode.defaultFor("a.txt.bak"))
    }

    @Test
    fun 無題はコード() = assertEquals(ViewMode.CODE, ViewMode.defaultFor(null))

    @Test
    fun 切り替えは行って戻る() {
        assertEquals(ViewMode.CODE, ViewMode.WORDPROCESSOR.toggled())
        assertEquals(ViewMode.WORDPROCESSOR, ViewMode.CODE.toggled())
    }
}
