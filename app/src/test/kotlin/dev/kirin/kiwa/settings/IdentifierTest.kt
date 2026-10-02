package dev.kirin.kiwa.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **E7 の欠陥③を機械で踏む** ── 日本語 IME を通した `go` が「ご」として登録された件。
 *
 * 直しは2段になっている（欄に `IME_FLAG_FORCE_ASCII` を頼む / 入ってきた文字を寄せて断る）。
 * **効くかが IME 次第の側は試験できない**ので、ここで見るのは受け止める方だけ ──
 * つまり「頼みが効かなかったとき」に何が起きるかを固定する試験。
 */
class IdentifierTest {

    @Test
    fun `そのまま通る名前`() {
        assertEquals("kotlin", Identifier.normalize("kotlin"))
        assertEquals("c++", Identifier.normalize("c++"))
        assertEquals("f#", Identifier.normalize("f#"))
        assertEquals("objective-c", Identifier.normalize("objective-c"))
        assertEquals("d.ts", Identifier.normalize("d.ts"))
    }

    @Test
    fun `大文字と前後の空白は寄せる`() {
        assertEquals("kotlin", Identifier.normalize("  Kotlin "))
        assertEquals("gradle", Identifier.normalize("GRADLE"))
    }

    @Test
    fun `★全角の英数は半角へ寄せる`() {
        // 日本語 IME が入っていると英数がこの形で来る。
        assertEquals("go", Identifier.normalize("ｇｏ"))
        assertEquals("go", Identifier.normalize("ＧＯ"))
        assertEquals("h2", Identifier.normalize("ｈ２"))
        assertEquals("c++", Identifier.normalize("ｃ＋＋"))
        assertEquals("objective-c", Identifier.normalize("ｏｂｊｅｃｔｉｖｅー ｃ".replace(" ", "")))
    }

    @Test
    fun `★寄せられない文字は断る`() {
        // これが登録されてしまったのが E7 の欠陥③。どの拡張子とも一致しないので、
        // 「設定したのに色が付かない」としか見えなかった。
        assertNull(Identifier.normalize("ご"))
        assertNull(Identifier.normalize("こーとりん"))
        assertNull(Identifier.normalize("kotlin と go"))
        assertNull(Identifier.normalize("日本語"))
    }

    @Test
    fun `空は断る`() {
        assertNull(Identifier.normalize(""))
        assertNull(Identifier.normalize("   "))
        assertNull(Identifier.normalize("　"))
    }

    @Test
    fun `記号だけの入力も範囲の中なら通す`() {
        // 拡張子として `.` を落とすのは呼ぶ側の仕事なので、ここでは通す。
        assertEquals(".", Identifier.normalize("."))
    }
}
