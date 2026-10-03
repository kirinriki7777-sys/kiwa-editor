package dev.kirin.kiwa.settings

import dev.kirin.editoradapter.EditorDocument
import dev.kirin.editoradapter.EditorEngine
import dev.kirin.editoradapter.EditorSearch
import dev.kirin.editoradapter.EditorTheme
import dev.kirin.editoradapter.ImeBoundary
import dev.kirin.editoradapter.TextPosition
import dev.kirin.editoradapter.TextRange
import dev.kirin.kiwa.ui.ThemeChoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 設定の型そのものの試験。**実機を要さない部分**だけをここで押さえる。
 *
 * 見ているのは3つ:
 *
 *   1. 保存して読み直すと同じ値になる（JSON の往復）
 *   2. 壊れた／古い設定ファイルでも起動できる（知らないキーは捨て、足りないキーは既定）
 *   3. **適用の口が全部の項目をエンジンへ渡している** ── 項目を足して
 *      `applyTo` へ書き忘れると、実機では「設定したのに反映されない」としてしか出ない
 */
class EditorSettingsTest {

    // ------------------------------------------------------------------
    // JSON の往復
    // ------------------------------------------------------------------

    @Test
    fun `既定値は往復する`() {
        val defaults = EditorSettings()
        assertEquals(defaults, EditorSettings.fromJson(defaults.toJson()))
    }

    @Test
    fun `既定は隠しファイルを出す`() {
        // **手本（fresh）に合わせた既定**。往復や画面の試験では出てこないので、
        // ここで固定しないと**黙って false へ戻っても誰も落ちない**。
        assertTrue(EditorSettings().showHiddenFiles)
    }

    @Test
    fun `全部の項目を変えても往復する`() {
        val settings = EditorSettings(
            fontSizeSp = 19f,
            font = "serif",
            showLineNumbers = false,
            showInvisibles = true,
            highlightCurrentLine = false,
            wordWrap = true,
            wrapLineLimit = 500,
            fullscreen = true,
            lineSpacing = 1.6f,
            cursorBlink = false,
            highlightMatchingBrackets = false,
            indentGuides = false,
            stickyScroll = false,
            pinLineNumbers = true,
            showSymbolRow = false,
            symbolRowKeys = listOf("raw.esc", "mod.ctrl"),
            theme = ThemeChoice.KIRI,
            indentWidth = 2,
            indentUsesTab = true,
            autoSave = true,
            autoClosePairs = false,
            autoIndent = false,
            trimTrailingWhitespace = true,
            insertFinalNewline = true,
            showHiddenFiles = false,
            fileSort = EditorSettings.FileSort.MODIFIED,
            defaultCharset = "windows-31j",
            defaultLineSeparator = "\r\n",
            rememberLastDirectory = false,
            maxOpenMegabytes = 32,
            perLanguage = mapOf("kotlin" to EditorSettings.LanguageRules(indentWidth = 2, indentUsesTab = false)),
            extraExtensions = mapOf("gradle" to "kotlin")
        )
        assertEquals(settings, EditorSettings.fromJson(settings.toJson()))
    }

    @Test
    fun `固定見出しの設定が書かれていない古い設定ファイルは入れる側で読む`() {
        val json = EditorSettings().toJson()
        json.remove("stickyScroll")
        assertTrue(EditorSettings.fromJson(json).stickyScroll)
        // 書いてあれば、切った値も読み戻す
        assertFalse(EditorSettings.fromJson(EditorSettings(stickyScroll = false).toJson()).stickyScroll)
    }

    @Test
    fun `記号キー列を空にした設定も往復する`() {
        // **「1つも出さない」は正しい設定**。空を既定で埋め直すと、選び直しても戻らなくなる。
        val settings = EditorSettings(symbolRowKeys = emptyList())
        assertEquals(emptyList<String>(), EditorSettings.fromJson(settings.toJson()).symbolRowKeys)
    }

    @Test
    fun `記号キー列の並びが書かれていなければ既定`() {
        val defaults = EditorSettings()
        val json = defaults.toJson()
        json.remove("symbolRowKeys")
        assertEquals(defaults.symbolRowKeys, EditorSettings.fromJson(json).symbolRowKeys)
    }

    @Test
    fun `記号キー列の並びは書いてある順のまま`() {
        // **順番が意味を持つ**（指で端から追う列なので）ので、読み書きで並べ替えない。
        val given = listOf("text.{", "mod.ctrl", "raw.tab")
        val settings = EditorSettings(symbolRowKeys = given)
        assertEquals(given, EditorSettings.fromJson(settings.toJson()).symbolRowKeys)
    }

    @Test
    fun `言語別の片側だけの上書きも往復する`() {
        val settings = EditorSettings(
            perLanguage = mapOf(
                "go" to EditorSettings.LanguageRules(indentUsesTab = true),
                "python" to EditorSettings.LanguageRules(indentWidth = 4)
            )
        )
        val back = EditorSettings.fromJson(settings.toJson())
        assertEquals(true, back.perLanguage["go"]?.indentUsesTab)
        assertNull(back.perLanguage["go"]?.indentWidth)
        assertEquals(4, back.perLanguage["python"]?.indentWidth)
        assertNull(back.perLanguage["python"]?.indentUsesTab)
    }

    /** 中身の無い上書きは保存で落とす。読み書きを繰り返すたびに増えていかないように。 */
    @Test
    fun `空の上書きは保存されない`() {
        val settings = EditorSettings(perLanguage = mapOf("rust" to EditorSettings.LanguageRules()))
        assertTrue(EditorSettings.fromJson(settings.toJson()).perLanguage.isEmpty())
    }

    // ------------------------------------------------------------------
    // 壊れた設定を食べても起動できる
    // ------------------------------------------------------------------

    @Test
    fun `空のJSONは全部既定になる`() {
        assertEquals(EditorSettings(), EditorSettings.fromJson(JSONObject()))
    }

    @Test
    fun `知らないキーは捨てて既定で埋める`() {
        val json = JSONObject("""{"minimap":true,"indentWidth":8}""")
        val settings = EditorSettings.fromJson(json)
        assertEquals(8, settings.indentWidth)
        assertEquals(EditorSettings().fontSizeSp, settings.fontSizeSp, 0.001f)
    }

    @Test
    fun `知らない列挙の名前は既定へ落ちる`() {
        val json = JSONObject("""{"theme":"NEON","fileSort":"COLOR"}""")
        val settings = EditorSettings.fromJson(json)
        assertEquals(ThemeChoice.DEFAULT, settings.theme)
        assertEquals(EditorSettings.FileSort.NAME, settings.fileSort)
    }

    /**
     * 「明るい」「生成り」「暗い」は 2026-10-02 に外した。**古い設定ファイルを読んだ途端に
     * 既定へ飛ぶと、選んでいた明暗が反転する**（明るいを選んでいた人が暗い画面で開く）ので、近いものへ寄せる。
     */
    @Test
    fun `外した配色の名前は近い色違いへ読み替える`() {
        fun read(name: String) = EditorSettings.fromJson(JSONObject("""{"theme":"$name"}""")).theme
        assertEquals(ThemeChoice.SUMI, read("DARK"))
        assertEquals(ThemeChoice.SHIRO, read("LIGHT"))
        assertEquals(ThemeChoice.KINARI, read("CREAM"))
        assertEquals(ThemeChoice.SYSTEM, read("SYSTEM"))
    }

    @Test
    fun `足した項目の既定はエンジンの素の振る舞いと同じ`() {
        // **足した日に画面も打ち心地も変わらない**ための既定。黙って変わると、
        // 設定を変更していない画面も、更新しただけで変わってしまう。
        val defaults = EditorSettings()
        assertEquals(1.0f, defaults.lineSpacing, 0.0001f)
        assertTrue(defaults.cursorBlink)
        assertTrue(defaults.highlightMatchingBrackets)
        assertTrue(defaults.indentGuides)
        assertTrue(defaults.stickyScroll)
        assertFalse(defaults.pinLineNumbers)
        assertTrue(defaults.autoClosePairs)
        assertTrue(defaults.autoIndent)
        // 保存で本文を変える2つは**既定で切る**（触っていないファイルのバイトを変えない）。
        assertFalse(defaults.trimTrailingWhitespace)
        assertFalse(defaults.insertFinalNewline)
    }

    @Test
    fun `範囲外の値は端へ寄せる`() {
        val json = JSONObject(
            """{"indentWidth":99,"fontSizeSp":900,"maxOpenMegabytes":0,"wrapLineLimit":-5,"lineSpacing":0.2}"""
        )
        val settings = EditorSettings.fromJson(json)
        assertEquals(1f, settings.lineSpacing, 0.0001f)
        assertEquals(16, settings.indentWidth)
        assertEquals(48f, settings.fontSizeSp, 0.001f)
        assertEquals(1, settings.maxOpenMegabytes)
        assertEquals(1, settings.wrapLineLimit)
    }

    /** 実在しない文字コードを選べてしまうと、読み込みで黙って無視されるだけになる。 */
    @Test
    fun `候補に無い文字コードは既定へ落ちる`() {
        val settings = EditorSettings.fromJson(JSONObject("""{"defaultCharset":"KOI8-R"}"""))
        assertEquals("UTF-8", settings.defaultCharset)
    }

    @Test
    fun `拡張子はドット付きで書かれても小文字ドット無しに揃う`() {
        val settings = EditorSettings.fromJson(JSONObject("""{"extensions":{".GRADLE":"Kotlin"}}"""))
        assertEquals(mapOf("gradle" to "kotlin"), settings.extraExtensions)
    }

    // ------------------------------------------------------------------
    // 引き当て
    // ------------------------------------------------------------------

    @Test
    fun `言語別の上書きが無ければ全体の既定`() {
        val settings = EditorSettings(indentWidth = 4, indentUsesTab = false)
        assertEquals(Pair(4, false), settings.indentFor("kotlin"))
        assertEquals(Pair(4, false), settings.indentFor(null))
    }

    @Test
    fun `言語別の上書きは埋まっている項目だけ効く`() {
        val settings = EditorSettings(
            indentWidth = 4,
            indentUsesTab = false,
            perLanguage = mapOf("go" to EditorSettings.LanguageRules(indentUsesTab = true))
        )
        assertEquals(Pair(4, true), settings.indentFor("go"))
        assertEquals(Pair(4, true), settings.indentFor("GO"))
        assertEquals(Pair(4, false), settings.indentFor("python"))
    }

    @Test
    fun `拡張子の追加は組み込みの表より先に引く`() {
        val settings = EditorSettings(extraExtensions = mapOf("gradle" to "kotlin", "sql" to "sqlish"))
        // 組み込みでは .gradle = groovy
        assertEquals("kotlin", settings.languageOf("build.gradle"))
        // 組み込みに無いものは足せる
        assertEquals("sqlish", settings.languageOf("schema.sql"))
        // 触っていない拡張子は組み込みのまま
        assertEquals("java", settings.languageOf("Main.java"))
        assertNull(settings.languageOf("notes.unknown"))
    }

    // ------------------------------------------------------------------
    // 適用 ── 項目を足して applyTo へ書き忘れていないか
    // ------------------------------------------------------------------

    @Test
    fun `applyTo が全部の項目をエンジンへ渡す`() {
        val engine = RecordingEngine()
        val settings = EditorSettings(
            fontSizeSp = 18f,
            font = "sans",
            showLineNumbers = false,
            showInvisibles = true,
            highlightCurrentLine = false,
            wordWrap = true,
            wrapLineLimit = 10_000,
            indentWidth = 2,
            indentUsesTab = true,
            lineSpacing = 1.4f,
            cursorBlink = false,
            highlightMatchingBrackets = false,
            indentGuides = false,
            stickyScroll = false,
            pinLineNumbers = true,
            autoClosePairs = false,
            autoIndent = false
        )
        settings.applyTo(engine, EditorTheme.KINARI, EditorSettings.Document("kotlin", "\r\n"))

        assertEquals(EditorTheme.KINARI, engine.seenTheme)
        assertEquals(18f, engine.seenTextSize, 0.001f)
        assertEquals("sans", engine.seenFont)
        assertEquals(false, engine.seenLineNumbers)
        assertEquals(true, engine.seenInvisibles)
        assertEquals(false, engine.seenCurrentLine)
        assertEquals("kotlin", engine.seenLanguage)
        assertEquals(Pair(2, true), engine.seenIndent)
        assertEquals("\r\n", engine.seenLineSeparator)
        assertTrue(engine.seenWordWrap)
        assertEquals(1.4f, engine.seenLineSpacing!!, 0.0001f)
        assertEquals(false, engine.seenCursorBlink)
        assertEquals(false, engine.seenMatchingBrackets)
        assertEquals(false, engine.seenIndentGuides)
        assertEquals(false, engine.seenStickyScroll)
        assertEquals(true, engine.seenPinLineNumbers)
        assertEquals(false, engine.seenAutoClosePairs)
        assertEquals(false, engine.seenAutoIndent)
    }

    @Test
    fun `ファイルを開いていなければ改行コードは既定`() {
        val engine = RecordingEngine()
        EditorSettings(defaultLineSeparator = "\r\n").applyTo(engine, EditorTheme.SUMI, null)
        assertEquals("\r\n", engine.seenLineSeparator)
        assertNull(engine.seenLanguage)
    }

    @Test
    fun `言語別のインデントが適用に乗る`() {
        val engine = RecordingEngine()
        EditorSettings(
            indentWidth = 4,
            indentUsesTab = false,
            perLanguage = mapOf("go" to EditorSettings.LanguageRules(indentUsesTab = true))
        ).applyTo(engine, EditorTheme.SUMI, EditorSettings.Document("go", "\n"))
        assertEquals(Pair(4, true), engine.seenIndent)
    }

    /**
     * 上限を超えた行数では、折り返しが有効でも切る。
     *
     * **これは設定の側の判断**（エンジンは言われたとおりに設定するだけ）なので、
     * 適用の口を通ったときに落ちていることをここで見る。
     */
    @Test
    fun `行数が上限を超えていたら折り返しは入らない`() {
        val engine = RecordingEngine(lines = 3000)
        EditorSettings(wordWrap = true, wrapLineLimit = 2000)
            .applyTo(engine, EditorTheme.SUMI, null)
        assertFalse(engine.seenWordWrap)

        val small = RecordingEngine(lines = 100)
        EditorSettings(wordWrap = true, wrapLineLimit = 2000)
            .applyTo(small, EditorTheme.SUMI, null)
        assertTrue(small.seenWordWrap)
    }

    /**
     * 何を渡されたかを覚えるだけのエンジン。
     *
     * 実物（Sora）は Android の `View` を要るので JVM の単体試験では動かない。
     * ここで見たいのは**設定が全部エンジンへ届いたか**であって、描画結果ではない。
     */
    private class RecordingEngine(lines: Int = 1) : EditorEngine {
        /** 行数はテキストの長さで作る（`positionOf` が行を返せればよい）。 */
        private val text = "\n".repeat(lines - 1)

        var seenTheme: EditorTheme? = null
        var seenTextSize = 0f
        var seenFont: String? = null
        var seenLineNumbers: Boolean? = null
        var seenInvisibles: Boolean? = null
        var seenCurrentLine: Boolean? = null
        var seenLanguage: String? = null
        var seenIndent: Pair<Int, Boolean>? = null
        var seenLineSeparator: String? = null
        var seenWordWrap = false
        var seenLineSpacing: Float? = null
        var seenCursorBlink: Boolean? = null
        var seenMatchingBrackets: Boolean? = null
        var seenIndentGuides: Boolean? = null
        var seenStickyScroll: Boolean? = null
        var seenPinLineNumbers: Boolean? = null
        var seenAutoClosePairs: Boolean? = null
        var seenAutoIndent: Boolean? = null

        override fun asView() = throw UnsupportedOperationException()
        override fun getText(): CharSequence = text
        override fun setText(text: CharSequence) = throw UnsupportedOperationException()
        override fun positionOf(index: Int): TextPosition {
            val line = text.take(index.coerceIn(0, text.length)).count { it == '\n' }
            return TextPosition(line, 0, index)
        }

        override fun setLineSeparator(separator: String) { seenLineSeparator = separator }
        override fun replaceRange(range: TextRange, replacement: CharSequence) =
            throw UnsupportedOperationException()
        override fun typeText(text: CharSequence) = throw UnsupportedOperationException()
        override fun getSelection(): TextRange = throw UnsupportedOperationException()
        override fun setSelection(range: TextRange) = throw UnsupportedOperationException()
        override fun canUndo() = false
        override fun undo() = Unit
        override fun canRedo() = false
        override fun redo() = Unit
        override fun setTheme(theme: EditorTheme) { seenTheme = theme }
        override fun setTextSize(sp: Float) { seenTextSize = sp }
        override fun setFont(name: String?) { seenFont = name }
        override fun setIndent(width: Int, useTab: Boolean) { seenIndent = Pair(width, useTab) }
        override fun setLineNumbersVisible(visible: Boolean) { seenLineNumbers = visible }
        override fun setShowInvisibles(visible: Boolean) { seenInvisibles = visible }
        override fun setHighlightCurrentLine(enabled: Boolean) { seenCurrentLine = enabled }
        override fun setLanguage(language: String?) { seenLanguage = language }
        override fun setWordWrap(enabled: Boolean) { seenWordWrap = enabled }
        override fun setLineSpacing(multiplier: Float) { seenLineSpacing = multiplier }
        override fun setCursorBlink(enabled: Boolean) { seenCursorBlink = enabled }
        override fun setAutoClosePairs(enabled: Boolean) { seenAutoClosePairs = enabled }
        override fun setAutoIndent(enabled: Boolean) { seenAutoIndent = enabled }
        override fun setHighlightMatchingBrackets(enabled: Boolean) { seenMatchingBrackets = enabled }
        override fun setIndentGuides(enabled: Boolean) { seenIndentGuides = enabled }
        override fun setStickyScroll(enabled: Boolean) { seenStickyScroll = enabled }
        override fun setPinLineNumbers(pinned: Boolean) { seenPinLineNumbers = pinned }
        override fun ime(): ImeBoundary = throw UnsupportedOperationException()

        // 検索は設定の適用に関わらない（`applyTo` は触らない）。
        override fun search(): EditorSearch = throw UnsupportedOperationException()

        // 文書（タブ）と変更の合図は設定の適用に関わらないので、ここでは動かない札を返す。
        override fun newDocument(text: CharSequence): EditorDocument = object : EditorDocument {}
        override fun showDocument(document: EditorDocument) = throw UnsupportedOperationException()
        override fun forgetDocument(document: EditorDocument) = throw UnsupportedOperationException()
        override fun setOnChangeListener(listener: Runnable?) = throw UnsupportedOperationException()
    }
}
