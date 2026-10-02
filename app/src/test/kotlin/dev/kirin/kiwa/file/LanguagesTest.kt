package dev.kirin.kiwa.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 拡張子から言語名を決める部分。**知らないものは null**（それらしい別の文法で代用しない）。 */
class LanguagesTest {

    @Test
    fun `拡張子から言語名を引く`() {
        assertEquals("kotlin", Languages.of("MainActivity.kt"))
        assertEquals("kotlin", Languages.of("build.gradle.kts"))
        assertEquals("java", Languages.of("Span.java"))
        assertEquals("xml", Languages.of("AndroidManifest.xml"))
        assertEquals("markdown", Languages.of("README.md"))
    }

    @Test
    fun `大文字の拡張子も同じ`() {
        assertEquals("python", Languages.of("SETUP.PY"))
    }

    @Test
    fun `設定ファイルの拡張子も引ける`() {
        assertEquals("json", Languages.of("settings.json"))
        assertEquals("yaml", Languages.of("config.yml"))
        assertEquals("groovy", Languages.of("build.gradle"))
        assertEquals("shellscript", Languages.of("install.sh"))
        assertEquals("typescript", Languages.of("main.tsx"))
        assertEquals("cpp", Languages.of("decoder.hpp"))
    }

    @Test
    fun `拡張子を持たない設定ファイルはファイル名で引く`() {
        // 先頭のドットは拡張子の区切りではない。表に入れておかないと引けない。
        assertEquals("shellscript", Languages.of(".bashrc"))
        assertEquals("shellscript", Languages.of(".ZSHRC"))
    }

    @Test
    fun `知らない拡張子と拡張子なしは null`() {
        // json5 は同梱していない。JSON で代用すると、
        // **ほとんどの行が合っているぶん間違っている行に気づけない**。
        assertNull(Languages.of("tsconfig.json5"))
        assertNull(Languages.of("Makefile"))
        assertNull(Languages.of("archive.tar."))
        assertNull(Languages.of(".gitignore"))
    }
}
