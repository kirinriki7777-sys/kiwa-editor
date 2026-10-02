package dev.kirin.kiwa.settings

import dev.kirin.editoradapter.EditorEngine
import dev.kirin.editoradapter.EditorTheme
import dev.kirin.kiwa.file.Languages
import dev.kirin.kiwa.symbols.SymbolKeys
import dev.kirin.kiwa.ui.ThemeChoice
import org.json.JSONObject

/**
 * **値の型が1つ、適用の口が1本。**
 *
 * ## なぜ `SharedPreferences` に直に書かないのか
 *
 * **言語別の設定が表現できないから。** あれは「全体の既定 ＋ 言語ごとの上書き」の2層で、
 * `theme=SUMI` のようなフラットなキーの並びには乗らない
 * （`lang.kotlin.indentWidth` のように名前へ階層を埋め込む形は、
 * 言語を1つ消したときに残骸が残るし、一覧を取るのに前方一致を書くことになる）。
 * ここでは値の型を1つ作り、保存は JSON 1ファイルにする。
 *
 * ## なぜ [applyTo] が1本なのか
 *
 * 設定を入れる場面が**3つある**からで、これは減らせない。
 *
 *   1. 起動したとき
 *   2. 設定画面で変えたとき
 *   3. **ファイルを開いたとき** ── 言語が決まって初めて言語別の上書きが効く
 *
 * 3箇所に別々の適用を書くと、項目を1つ足したときにどれか1箇所を忘れる。
 * その忘れ方は「**設定したのに反映されない**」という形で出て、しかも
 * 「起動直後だけ効く」「ファイルを開くと戻る」のように条件付きなので気づきにくい。
 * だから**エンジンへ設定を入れるのはこのファイルの中だけ**にして、
 * `tools/check-settings.sh` が `:app` の他のファイルから呼ばれていないことを機械で見る。
 */
data class EditorSettings(
    // ------------------------------------------------------------------ 表示
    val fontSizeSp: Float = 14f,
    /** 書体の名前。実体はエンジンが持つ（[EditorEngine.setFont]）。 */
    val font: String = FONT_MONOSPACE,
    val showLineNumbers: Boolean = true,
    val showInvisibles: Boolean = false,
    val highlightCurrentLine: Boolean = true,
    val wordWrap: Boolean = false,
    /**
     * 折り返しを許す行数の上限。
     *
     * 実機実測（S3b / Redmi Pad 2 Pro）: 折り返し有効時の先頭への1文字挿入は
     * 1,000行 3.7ms / 5,000行 13.1ms / 20,000行 56.1ms（＝1フレームの3.4倍）。
     * 無効なら行数に依らず 0.5〜1.1ms。**上限は設定で動かせるが、既定は控えめに置く。**
     */
    val wrapLineLimit: Int = 2000,
    val fullscreen: Boolean = false,
    /**
     * 行の高さ（文字の高さに掛ける倍率）。**既定の 1.0 はエンジンの素のまま**で、
     * ここから下の項目（2026-10-02 に足した分）はどれも既定がエンジンの素の振る舞いに揃えてある ──
     * 足した日に画面も打ち心地も変わらないように。
     */
    val lineSpacing: Float = 1.0f,
    val cursorBlink: Boolean = true,
    /** 括弧の対応を強調する。 */
    val highlightMatchingBrackets: Boolean = true,
    /** インデントガイド。**線が出るかは言語次第**（ブロックの範囲を文法が教える場合だけ）。 */
    val indentGuides: Boolean = true,
    /** 横スクロールしても行番号を左端に留める。 */
    val pinLineNumbers: Boolean = false,

    // ------------------------------------------------------------------ 記号キー列
    /**
     * 記号キー列を出すか（E12）。**出るのはソフトキーボードが見えている間だけ**で、
     * ここが true でも物理キーボードだけのときは出ない ── 物理キーボードがあるなら
     * Tab も Esc も矢印も本物がある。
     */
    val showSymbolRow: Boolean = true,
    /**
     * 記号キー列の並び。中身は [SymbolKeys] の id。
     *
     * **並びそのものを持つ**（「どれを出すか」の集合ではない）── 指で押す列は
     * 端から順に目で追うので、順番が意味を持つ。設定画面から選べるのは今のところ
     * 「どれを出すか」だけで、そこで選んだものは [SymbolKeys.inCanonicalOrder] の順に入る。
     */
    val symbolRowKeys: List<String> = SymbolKeys.DEFAULT,

    // ------------------------------------------------------------------ 配色
    /** 設定画面では「表示」の先頭に出る（2026-10-02 に「配色」の節を畳んだ）。 */
    val theme: ThemeChoice = ThemeChoice.DEFAULT,

    // ------------------------------------------------------------------ 編集
    val indentWidth: Int = 4,
    val indentUsesTab: Boolean = false,
    /**
     * 画面を離れるときに保存するか。
     *
     * **時間で走らせない。** 打っている最中に書き戻すと、母艦側が同じファイルを
     * 開いている場合に中途半端な状態が同期される（この端末は Syncthing で母艦と繋がっている）。
     * 区切りがはっきりしているのは「この画面から離れた」の一点だけ。
     */
    val autoSave: Boolean = false,
    /**
     * 括弧を自動で閉じる。**どの組を閉じるかは言語が決める**（`EditorEngine.setAutoClosePairs`）。
     * 既定は入れる。記号キー列の `(` も物理キーと同じく閉じる。
     */
    val autoClosePairs: Boolean = true,
    /** 改行で前の行の字下げを引き継ぐ。 */
    val autoIndent: Boolean = true,
    /**
     * 保存するときに行末の空白を消す。**既定は切ってある。**
     *
     * 入れると「触っていないファイルを保存してもバイトが変わらない」（`TextFile` の的）が崩れる ──
     * 開いて保存しただけで差分が出る。必要な場合に有効にする設定なので、
     * 既定では本文を書き換えない。
     */
    val trimTrailingWhitespace: Boolean = false,
    /** 保存するときに、最後の行が改行で終わっていなければ足す。**既定は切ってある**（理由は上と同じ）。 */
    val insertFinalNewline: Boolean = false,

    // ------------------------------------------------------------------ ファイル
    /**
     * 隠しファイルをファイルの一覧（引き出し / パレット）に出すか。
     *
     * **既定は出す。** コードを触る人のフォルダでは、`.gitignore`・`.github/`・`.bashrc` のような
     * 隠しファイルにこそ用がある。隠すのが親切なのは、文書や写真を扱うアプリの場合。
     */
    val showHiddenFiles: Boolean = true,
    val fileSort: FileSort = FileSort.NAME,
    /**
     * 文字コードの既定。
     *
     * **判別の結果を上書きするものではない。** 読み込みは候補を順に復号して
     * 符号化し直し、**元のバイト列と一致した最初のもの**を採る（`TextFile.decode`）。
     * ASCII だけのファイルはどの候補でも一致するので、そこで**何を名乗るか**がこの設定。
     * つまり効くのは「新しく打った日本語をどう保存するか」で、既存のバイト列は動かさない。
     */
    val defaultCharset: String = "UTF-8",
    /**
     * 改行コードの既定。**改行が1つも無いファイル**（1行だけ / 空）に行を足すときに使う。
     * 改行があるファイルはそちらの多数派に合わせる（`TextFile.dominantLineSeparator`）。
     */
    val defaultLineSeparator: String = "\n",
    val rememberLastDirectory: Boolean = true,
    /** 開けるファイルの上限（MB）。 */
    val maxOpenMegabytes: Int = 8,

    // ------------------------------------------------------------------ 言語別
    /** 言語名 → 上書き。載っていない言語は全体の既定に従う。 */
    val perLanguage: Map<String, LanguageRules> = emptyMap(),
    /**
     * 拡張子 → 言語名の追加。**組み込みの表より先に引く**ので、上書きにもなる。
     * 拡張子は小文字・ドット無し。
     */
    val extraExtensions: Map<String, String> = emptyMap()
) {

    /** 言語ごとの上書き。埋まっていない項目は全体の既定に従う（＝ null）。 */
    data class LanguageRules(
        val indentWidth: Int? = null,
        val indentUsesTab: Boolean? = null
    ) {
        fun isEmpty(): Boolean = indentWidth == null && indentUsesTab == null
    }

    enum class FileSort(val label: String) {
        NAME("名前"),
        MODIFIED("更新が新しい順"),
        SIZE("大きい順")
    }

    /** 今開いている文書のうち、**設定の適用に効くもの**だけ。開いていなければ null を渡す。 */
    data class Document(
        /** 構文ハイライトの言語名。null なら色を付けない。 */
        val language: String?,
        /**
         * このファイルが使っている改行コード。null なら [defaultLineSeparator]。
         * **推定して直すのではなく、元に合わせる**ためのもの。
         */
        val lineSeparator: String?
    )

    // ------------------------------------------------------------------
    // 適用 ── エンジンへ設定を入れるのはここだけ
    // ------------------------------------------------------------------

    /**
     * 設定をエンジンへ入れる**唯一の口**。起動時・設定を変えた時・ファイルを開いた時の
     * 3箇所から、同じこれを呼ぶ。
     *
     * @param editorTheme エディタの中の配色。[theme] から解決したもの ──
     *        「端末に合わせる」は端末の明暗を読むので [android.content.Context] が要り、
     *        ここへ持ち込むとこの型が Android なしでは試せなくなる。
     *        呼び出し側はアプリ側の色を作るときに同じ解決を1回だけやっている
     * @param document 開いている文書。null なら未オープン（既定で埋める）
     */
    fun applyTo(engine: EditorEngine, editorTheme: EditorTheme, document: Document?) {
        engine.setTheme(editorTheme)
        engine.setTextSize(fontSizeSp)
        engine.setFont(font)
        engine.setLineNumbersVisible(showLineNumbers)
        engine.setShowInvisibles(showInvisibles)
        engine.setHighlightCurrentLine(highlightCurrentLine)
        engine.setLineSpacing(lineSpacing)
        engine.setCursorBlink(cursorBlink)
        engine.setHighlightMatchingBrackets(highlightMatchingBrackets)
        engine.setIndentGuides(indentGuides)
        engine.setPinLineNumbers(pinLineNumbers)
        engine.setAutoClosePairs(autoClosePairs)
        engine.setAutoIndent(autoIndent)

        // 言語は文字コードと同じ扱い ── 開いたファイルが決めるもので、設定は上書きの表しか持たない。
        engine.setLanguage(document?.language)
        // 言語より後に入れる**必要は無い**（エンジンが言語を替えても保持する契約）が、
        // 順番に頼らないで済むように、ここでも言語の後に置いておく。
        val indent = indentFor(document?.language)
        engine.setIndent(indent.first, indent.second)

        engine.setLineSeparator(document?.lineSeparator ?: defaultLineSeparator)
        // 折り返しは行数で切る。判断は呼び出し側（＝ここ）の責務。
        engine.setWordWrap(wordWrap && !exceedsWrapLimit(engine))
    }

    /** 折り返しを切るべき大きさか。行数はエンジンに聞く。 */
    fun exceedsWrapLimit(engine: EditorEngine): Boolean = lineCount(engine) > wrapLineLimit

    fun lineCount(engine: EditorEngine): Int =
        engine.positionOf(engine.getText().length).line() + 1

    // ------------------------------------------------------------------
    // 引き当て
    // ------------------------------------------------------------------

    /** [language] に効くインデント（幅, タブを使うか）。上書きが無ければ全体の既定。 */
    fun indentFor(language: String?): Pair<Int, Boolean> {
        val rules = language?.let { perLanguage[it.lowercase()] }
        return Pair(
            rules?.indentWidth ?: indentWidth,
            rules?.indentUsesTab ?: indentUsesTab
        )
    }

    /**
     * ファイル名から言語名を引く。**設定の追加分を先に見る**ので、
     * 組み込みの表を上書きできる（`.gradle` を groovy でなく kotlin にする、など）。
     */
    fun languageOf(fileName: String): String? {
        val lower = fileName.lowercase()
        val dot = lower.lastIndexOf('.')
        if (dot > 0 && dot < lower.length - 1) {
            extraExtensions[lower.substring(dot + 1)]?.let { return it }
        }
        return Languages.of(fileName)
    }

    fun maxOpenBytes(): Long = maxOpenMegabytes.toLong() * 1024 * 1024

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("fontSizeSp", fontSizeSp.toDouble())
        put("font", font)
        put("lineNumbers", showLineNumbers)
        put("invisibles", showInvisibles)
        put("highlightCurrentLine", highlightCurrentLine)
        put("wordWrap", wordWrap)
        put("wrapLineLimit", wrapLineLimit)
        put("fullscreen", fullscreen)
        put("lineSpacing", lineSpacing.toDouble())
        put("cursorBlink", cursorBlink)
        put("highlightMatchingBrackets", highlightMatchingBrackets)
        put("indentGuides", indentGuides)
        put("pinLineNumbers", pinLineNumbers)
        put("symbolRow", showSymbolRow)
        put("symbolRowKeys", org.json.JSONArray(symbolRowKeys))
        put("theme", theme.name)
        put("indentWidth", indentWidth)
        put("indentUsesTab", indentUsesTab)
        put("autoSave", autoSave)
        put("autoClosePairs", autoClosePairs)
        put("autoIndent", autoIndent)
        put("trimTrailingWhitespace", trimTrailingWhitespace)
        put("insertFinalNewline", insertFinalNewline)
        put("showHiddenFiles", showHiddenFiles)
        put("fileSort", fileSort.name)
        put("defaultCharset", defaultCharset)
        put("defaultLineSeparator", defaultLineSeparator)
        put("rememberLastDirectory", rememberLastDirectory)
        put("maxOpenMegabytes", maxOpenMegabytes)
        put("languages", JSONObject().apply {
            for ((name, rules) in perLanguage) {
                if (rules.isEmpty()) continue
                put(name, JSONObject().apply {
                    rules.indentWidth?.let { put("indentWidth", it) }
                    rules.indentUsesTab?.let { put("indentUsesTab", it) }
                })
            }
        })
        put("extensions", JSONObject().apply {
            for ((extension, language) in extraExtensions) put(extension, language)
        })
    }

    companion object {
        const val VERSION = 1

        const val FONT_MONOSPACE = "monospace"

        /** 選べる書体。名前はエンジンへそのまま渡る（[EditorEngine.setFont]）。 */
        val FONTS = listOf(FONT_MONOSPACE to "等幅", "sans" to "ゴシック", "serif" to "明朝")

        /**
         * 選べる文字コード。`TextFile` の候補と揃えてある ──
         * ここにしか無い名前を選べても、読み込みでは使われない。
         */
        val CHARSETS = listOf("UTF-8", "windows-31j", "EUC-JP", "ISO-2022-JP", "ISO-8859-1")

        val LINE_SEPARATORS = listOf("\n" to "LF (Unix)", "\r\n" to "CRLF (Windows)", "\r" to "CR (旧 Mac)")

        /** 選べる行の高さ。指で選ぶので段階を切ってある（JSON を直に書けば 1.0〜3.0 の間で何でも入る）。 */
        val LINE_SPACINGS = listOf(1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)

        /**
         * 読み込み。**知らないキーは黙って捨て、足りないキーは既定で埋める。**
         * 設定ファイルを1バージョン跨いでも起動できなくならないように。
         */
        fun fromJson(json: JSONObject): EditorSettings {
            val defaults = EditorSettings()
            return EditorSettings(
                fontSizeSp = json.optDouble("fontSizeSp", defaults.fontSizeSp.toDouble())
                    .toFloat().coerceIn(6f, 48f),
                font = json.optString("font", defaults.font),
                showLineNumbers = json.optBoolean("lineNumbers", defaults.showLineNumbers),
                showInvisibles = json.optBoolean("invisibles", defaults.showInvisibles),
                highlightCurrentLine = json.optBoolean("highlightCurrentLine", defaults.highlightCurrentLine),
                wordWrap = json.optBoolean("wordWrap", defaults.wordWrap),
                wrapLineLimit = json.optInt("wrapLineLimit", defaults.wrapLineLimit).coerceIn(1, 1_000_000),
                fullscreen = json.optBoolean("fullscreen", defaults.fullscreen),
                lineSpacing = json.optDouble("lineSpacing", defaults.lineSpacing.toDouble())
                    .toFloat().takeIf { !it.isNaN() }?.coerceIn(1f, 3f) ?: defaults.lineSpacing,
                cursorBlink = json.optBoolean("cursorBlink", defaults.cursorBlink),
                highlightMatchingBrackets = json.optBoolean(
                    "highlightMatchingBrackets", defaults.highlightMatchingBrackets
                ),
                indentGuides = json.optBoolean("indentGuides", defaults.indentGuides),
                pinLineNumbers = json.optBoolean("pinLineNumbers", defaults.pinLineNumbers),
                showSymbolRow = json.optBoolean("symbolRow", defaults.showSymbolRow),
                symbolRowKeys = readSymbolRowKeys(json.optJSONArray("symbolRowKeys"))
                    ?: defaults.symbolRowKeys,
                // 外した配色の名前（DARK など）は近い色違いへ読み替える。
                theme = ThemeChoice.fromStored(json.optString("theme")) ?: defaults.theme,
                indentWidth = json.optInt("indentWidth", defaults.indentWidth).coerceIn(1, 16),
                indentUsesTab = json.optBoolean("indentUsesTab", defaults.indentUsesTab),
                autoSave = json.optBoolean("autoSave", defaults.autoSave),
                autoClosePairs = json.optBoolean("autoClosePairs", defaults.autoClosePairs),
                autoIndent = json.optBoolean("autoIndent", defaults.autoIndent),
                trimTrailingWhitespace = json.optBoolean("trimTrailingWhitespace", defaults.trimTrailingWhitespace),
                insertFinalNewline = json.optBoolean("insertFinalNewline", defaults.insertFinalNewline),
                showHiddenFiles = json.optBoolean("showHiddenFiles", defaults.showHiddenFiles),
                fileSort = enumOrDefault(json.optString("fileSort"), defaults.fileSort),
                defaultCharset = json.optString("defaultCharset", defaults.defaultCharset)
                    .takeIf { it in CHARSETS } ?: defaults.defaultCharset,
                defaultLineSeparator = json.optString("defaultLineSeparator", defaults.defaultLineSeparator)
                    .takeIf { name -> LINE_SEPARATORS.any { it.first == name } } ?: defaults.defaultLineSeparator,
                rememberLastDirectory = json.optBoolean("rememberLastDirectory", defaults.rememberLastDirectory),
                maxOpenMegabytes = json.optInt("maxOpenMegabytes", defaults.maxOpenMegabytes).coerceIn(1, 512),
                perLanguage = readLanguages(json.optJSONObject("languages")),
                extraExtensions = readExtensions(json.optJSONObject("extensions"))
            )
        }

        private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback

        /**
         * 並びを読む。**書いてあれば空の並びも尊重する**（「1つも出さない」は正しい設定）ので、
         * 既定で埋めるのはキーそのものが無いときだけ。知らない id は
         * [SymbolKeys.parse] が出すときに落ちるので、ここでは触らない。
         */
        private fun readSymbolRowKeys(array: org.json.JSONArray?): List<String>? {
            array ?: return null
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val id = array.optString(i, "")
                if (id.isNotEmpty()) out.add(id)
            }
            return out
        }

        private fun readLanguages(json: JSONObject?): Map<String, LanguageRules> {
            json ?: return emptyMap()
            val out = LinkedHashMap<String, LanguageRules>()
            for (name in json.keys()) {
                val entry = json.optJSONObject(name) ?: continue
                val rules = LanguageRules(
                    indentWidth = if (entry.has("indentWidth")) {
                        entry.optInt("indentWidth").coerceIn(1, 16)
                    } else {
                        null
                    },
                    indentUsesTab = if (entry.has("indentUsesTab")) entry.optBoolean("indentUsesTab") else null
                )
                // 中身が空の上書きは、無いのと同じなので落とす（読み書きを繰り返しても増えないように）。
                if (!rules.isEmpty()) out[name.lowercase()] = rules
            }
            return out
        }

        private fun readExtensions(json: JSONObject?): Map<String, String> {
            json ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in json.keys()) {
                val language = json.optString(key).takeIf { it.isNotBlank() } ?: continue
                // ドット付きで書かれても受ける。表の側は「ドット無しの小文字」に揃える。
                out[key.lowercase().removePrefix(".")] = language.lowercase()
            }
            return out
        }
    }
}
