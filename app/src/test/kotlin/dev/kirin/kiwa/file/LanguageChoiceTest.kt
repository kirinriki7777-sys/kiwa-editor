package dev.kirin.kiwa.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 言語の手動選択（U3）。見たいのは3つ ── **手で選んだものが名前より勝つ**こと、
 * **自動へ戻せる**こと、**選択肢が組み込みの表を全部含む**こと（選べない言語を作らない）。
 */
class LanguageChoiceTest {

    @Test
    fun `自動ならファイル名から引いた言語をそのまま使う`() {
        assertEquals("kotlin", LanguageChoice.Auto.resolve("kotlin"))
        assertNull(LanguageChoice.Auto.resolve(null))
    }

    @Test
    fun `手で選んだ言語は名前より勝つ`() {
        assertEquals("python", LanguageChoice.Fixed("python").resolve("kotlin"))
        // 無題のタブ（名前から引けない）でも選べる。
        assertEquals("python", LanguageChoice.Fixed("python").resolve(null))
    }

    @Test
    fun `色を付けないを選べば名前が何でも色を付けない`() {
        assertNull(LanguageChoice.Fixed(null).resolve("kotlin"))
    }

    @Test
    fun `先頭は自動、次が色を付けない、その後に言語名`() {
        val options = LanguageChoice.options("kotlin", LanguageChoice.Auto, emptyList())
        assertEquals(LanguageChoice.Auto, options[0].choice)
        assertTrue(options[0].label, options[0].label.contains("kotlin"))
        assertEquals(LanguageChoice.Fixed(null), options[1].choice)
        val names = options.drop(2).map { it.label }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `組み込みの表の言語は全部選べる`() {
        val options = LanguageChoice.options(null, LanguageChoice.Auto, emptyList())
        val choosable = options.mapNotNull { (it.choice as? LanguageChoice.Fixed)?.language }.toSet()
        assertTrue(choosable.containsAll(Languages.allLanguageNames()))
    }

    @Test
    fun `設定で足した言語も並び、重複しない`() {
        val options = LanguageChoice.options(null, LanguageChoice.Auto, listOf("Zig", "kotlin"))
        val labels = options.map { it.label }
        assertTrue(labels.contains("zig"))
        assertEquals(1, labels.count { it == "kotlin" })
    }

    @Test
    fun `今の選択に印が1つだけ付く`() {
        val auto = LanguageChoice.options("kotlin", LanguageChoice.Auto, emptyList())
        assertEquals(listOf(0), auto.indices.filter { auto[it].selected })

        val fixed = LanguageChoice.options("kotlin", LanguageChoice.Fixed("python"), emptyList())
        assertEquals(listOf("python"), fixed.filter { it.selected }.map { it.label })
    }

    @Test
    fun `短い名前は手動かどうかを見分けられる`() {
        assertEquals("kotlin", LanguageChoice.label("kotlin", LanguageChoice.Auto))
        assertEquals("python（手動）", LanguageChoice.label("kotlin", LanguageChoice.Fixed("python")))
        assertEquals("色を付けない", LanguageChoice.label(null, LanguageChoice.Auto))
        assertEquals("色を付けない（手動）", LanguageChoice.label("kotlin", LanguageChoice.Fixed(null)))
    }
}
