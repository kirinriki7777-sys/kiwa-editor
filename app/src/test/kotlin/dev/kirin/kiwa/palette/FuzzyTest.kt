package dev.kirin.kiwa.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    @Test
    fun `飛ばし飛ばしで当たる`() {
        assertNotNull(Fuzzy.score("ui/PaletteView.kt", "pv"))
        assertNotNull(Fuzzy.score("app/src/main/MainActivity.kt", "mainact"))
    }

    @Test
    fun `順番が違えば当たらない`() {
        // 部分列なので順序に縛られる。
        assertNull(Fuzzy.score("file.save", "evas"))
        assertNull(Fuzzy.score("file.save", "xyz"))
    }

    @Test
    fun `空白で区切ると順番の縛りが外れる`() {
        // 「その2語が入っているもの」を探すとき、順番まで覚えていなくても引ける。
        assertNotNull(Fuzzy.score("file.save", "save file"))
        assertNull(Fuzzy.score("file.save", "save zzz"))
    }

    @Test
    fun `語が空なら全部に当たる`() {
        assertEquals(0, Fuzzy.score("なんでも", ""))
        assertEquals(0, Fuzzy.score("なんでも", "   "))
    }

    @Test
    fun `大文字と小文字を区別しない`() {
        assertNotNull(Fuzzy.score("MainActivity.kt", "mainactivity"))
        assertNotNull(Fuzzy.score("mainactivity.kt", "MAIN"))
    }

    @Test
    fun `日本語でも引ける`() {
        assertNotNull(Fuzzy.score("保存 file.save", "保存"))
        assertNotNull(Fuzzy.score("折り返し view.wrap", "折返"))
    }

    @Test
    fun `続けて当たった方が点が高い`() {
        val together = Fuzzy.score("save", "sav")!!
        val apart = Fuzzy.score("s_a_v_e", "sav")!!
        assertTrue("続いている方が上に来ないと、打った通りの名前が沈む", together > apart)
    }

    @Test
    fun `区切りの直後は点が付く`() {
        // `file.save` の `s` は語の頭なので、ただ途中で当たったものより上に来る。
        val head = Fuzzy.score("file.save", "s")!!
        val middle = Fuzzy.score("filesave", "s")!!
        assertTrue(head > middle)
    }

    @Test
    fun `並べ替えは点の高い順で同点は元の順`() {
        val items = listOf("zzz save", "file.save", "saved")
        val ranked = Fuzzy.rank(items, "save", 10) { it }
        assertEquals("saved", ranked.first())
        assertEquals(3, ranked.size)
    }

    @Test
    fun `当たらないものは並びから落ちる`() {
        val ranked = Fuzzy.rank(listOf("alpha", "beta"), "zzz", 10) { it }
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `語が空なら元の並びのまま上から`() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf("a", "b"), Fuzzy.rank(items, "", 2) { it })
    }
}
