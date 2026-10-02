package dev.kirin.kiwa.edit

import dev.kirin.kiwa.file.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LineComments] の対応表そのものの試験。
 *
 * **件数でなく集合で [Languages] と突き合わせる** ── 件数だけだと言語名を1文字
 * 打ち間違えた行が黙って通る（`tools/check_commands_table.py` と同じ考え方。
 * memory: 一覧化の受入は集合の一致で書く）。
 */
class LineCommentsTest {

    @Test
    fun `対応表の言語の集合が Languages と完全一致する`() {
        assertEquals(Languages.allLanguageNames(), LineComments.languages())
    }

    @Test
    fun `対応表は18言語を持つ`() {
        assertEquals(18, LineComments.languages().size)
    }

    @Test
    fun `スラッシュ系の言語`() {
        for (language in listOf("kotlin", "java", "groovy", "javascript", "typescript", "c", "cpp", "rust", "go")) {
            assertEquals(language, "//", LineComments.of(language))
        }
    }

    @Test
    fun `シャープ系の言語`() {
        for (language in listOf("python", "shellscript", "yaml")) {
            assertEquals(language, "#", LineComments.of(language))
        }
    }

    @Test
    fun `ハイフン系の言語`() {
        assertEquals("--", LineComments.of("lua"))
    }

    @Test
    fun `行コメントが無い言語は null`() {
        for (language in listOf("html", "css", "json", "xml", "markdown")) {
            assertNull(language, LineComments.of(language))
        }
    }

    @Test
    fun `知らない言語名は null`() {
        assertNull(LineComments.of("brainfuck"))
    }

    @Test
    fun `言語が null なら null`() {
        assertNull(LineComments.of(null))
    }

    @Test
    fun `行コメントが無い言語も表には載っている 落とさず null で持つ`() {
        // 「無し」を表から落とすと、載せ忘れと区別が付かなくなる。
        assertTrue("html" in LineComments.languages())
        assertTrue(LineComments.languages().contains("markdown"))
    }
}
