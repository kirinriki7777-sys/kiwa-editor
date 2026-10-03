package dev.kirin.kiwa.engine.sora

import android.content.Context
import android.util.Log
import dev.kirin.editoradapter.EditorTheme
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.langs.textmate.registry.reader.LanguageDefinitionReader
import io.github.rosemoe.sora.langs.textmate.utils.ColorUtils
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import org.eclipse.tm4e.core.internal.theme.raw.RawTheme
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * TextMate の文法とテーマの用意。**engine-sora の中だけの話**で、
 * `:app` からは [dev.kirin.editoradapter.EditorEngine.setLanguage] の名前しか見えない。
 *
 * ## 文法は要求されたものだけ読む
 *
 * `GrammarRegistry.loadGrammars("textmate/languages.json")` は
 * 一覧に載った文法を**全部その場で読んで構文解析する**（registry/GrammarRegistry.java:177 →
 * :202 で1件ずつ `doLoadGrammar`）。同梱している文法は合わせて 730KB で、
 * うち JavaScript 1本が 240KB ある。開いたファイルに関係ない分まで
 * 毎回読むことになるので、**名前で引いて1件だけ読む**形にした。
 *
 * ## テーマは先に全部読む
 *
 * こちらは8枚で 60KB ほど。配色は利用者が切り替える操作なので、
 * 選んだ瞬間に読み込みが挟まらないよう先に読む。
 *
 * ## 同梱物の出どころ
 *
 * `assets/textmate/` は sora-editor 0.24.6 のサンプルアプリが持っているものをそのまま置いた
 * （文法は元が VS Code / MIT）。テーマの
 * `sumi` `yoru` `mori` `beni` `kami` `shiro` `kinari` `kiri` の8枚はアプリ用に作ったテーマ。
 * 選択肢に含めない `darcula` / `quietlight` / `cream` は同梱しない。
 */
internal object TextMateSetup {

    private const val TAG = "KiwaTextMate"
    private const val LANGUAGES = "textmate/languages.json"

    /** 埋め込みの文法をたどる深さの上限。定義が輪になっていても止まるように。 */
    private const val MAX_EMBED_DEPTH = 4

    private var ready = false

    /** 名前 → 文法の定義。`languages.json` を読むまで空。 */
    private val definitions = HashMap<String, GrammarDefinition>()

    /** すでに読み込んだ文法のスコープ名。二度読みしないための札。 */
    private val loaded = HashSet<String>()

    /**
     * [EditorTheme] と同梱テーマのファイル名の対応。**暗いかどうかは Sora へ申告が要る**
     * （配色の既定が変わる）ので、読むときに [EditorTheme.isDark] を渡す。
     *
     * **全部の [EditorTheme] がここに要る** ── 抜けると [colorScheme] の `getValue` で落ちる。
     * `TextMateThemesTest` が機械で見ている。
     */
    internal val themes = mapOf(
        EditorTheme.SUMI to "sumi",
        EditorTheme.YORU to "yoru",
        EditorTheme.MORI to "mori",
        EditorTheme.BENI to "beni",
        EditorTheme.KAMI to "kami",
        EditorTheme.SHIRO to "shiro",
        EditorTheme.KINARI to "kinari",
        EditorTheme.KIRI to "kiri"
    )

    /**
     * 文法とテーマを使える状態にする。2回目以降は何もしない。
     *
     * assets から読むので [Context] が要る。アプリ側の Context を握らないよう
     * `applicationContext` にしてある（エディタは Activity より長生きしうる）。
     */
    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(context.applicationContext.assets)
        )
        // ★ テーマを設定する前に GrammarRegistry を作っておく。
        //
        // 文法側のテーマ（トークンの色を割り当てる表）は、**ThemeRegistry の通知でしか届かない**
        // ── GrammarRegistry は getInstance() の中で購読を始め
        // （registry/GrammarRegistry.java:69 → :81）、その後の setTheme を受けて
        // 自分の registry へ渡す（同:252）。先にテーマを設定してしまうと、
        // その1回ぶんの通知を誰も受け取らない。
        //
        // **症状は「色が付かない」だけで、例外も警告も出ない。** 実機で
        // 地と現在行はテーマどおりなのに文字だけ全部同じ灰色、という形で出た（2026-09-06）。
        GrammarRegistry.getInstance()
        loadThemes()
        loadDefinitions()
        ready = true
    }

    private fun loadThemes() {
        val registry = ThemeRegistry.getInstance()
        for ((theme, name) in themes) {
            val dark = theme.isDark
            val path = "textmate/$name.json"
            try {
                val stream = FileProviderRegistry.getInstance().tryGetInputStream(path)
                registry.loadTheme(
                    ThemeModel(IThemeSource.fromInputStream(stream, path, null), name)
                        .apply { isDark = dark }
                )
            } catch (e: Exception) {
                // 配色が1枚読めなくても編集はできる。落とさずに記録だけ残す。
                Log.w(TAG, "failed to load theme: $name", e)
            }
        }
    }

    private fun loadDefinitions() {
        try {
            for (definition in LanguageDefinitionReader.read(LANGUAGES)) {
                definitions[definition.name.lowercase()] = definition
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read $LANGUAGES", e)
        }
    }

    /** 同梱している言語名。順序は `languages.json` のまま。 */
    fun languageNames(context: Context): List<String> {
        ensure(context)
        return definitions.keys.sorted()
    }

    /**
     * 言語名から [Language] を作る。**知らない名前と null は色を付けない言語を返す**
     * ── 呼び出し側は、エンジンがその文法を持っているかを知らなくていい。
     */
    fun language(context: Context, name: String?): Language {
        if (name.isNullOrBlank()) return EmptyLanguage()
        ensure(context)
        val definition = definitions[name.lowercase()] ?: return EmptyLanguage()
        // scopeName は「文法を1つに定める名前」なので、無い定義は使いようがない。
        val scope = definition.scopeName ?: return EmptyLanguage()
        try {
            load(definition, 0)
            // 識別子は集めない。補完を持たないので、集めても捨てるだけ。
            return TextMateLanguage.create(scope, false)
        } catch (e: Exception) {
            Log.w(TAG, "failed to load grammar: $name ($scope)", e)
            loaded.remove(scope)
            return EmptyLanguage()
        }
    }

    /**
     * 文法を1つ読む。**埋め込みの文法を先に入れる。**
     *
     * Markdown は fenced block ごとに html / javascript / java / xml / python を、
     * HTML は script タグの中に javascript を持つ。埋め込み先が登録されていないと
     * `findGrammarIds`（registry/GrammarRegistry.java:273）が id を引けず、
     * **その範囲だけ色が落ちる**（例外は出ず、`CANNOT find rule for scopeName` の警告が1行出るだけ）。
     *
     * 深さを切ってあるのは、html → javascript → html のように定義が輪になっていても
     * 止まるようにするため。[loaded] が同じ役目をするが、二重の保険にしておく。
     */
    private fun load(definition: GrammarDefinition, depth: Int) {
        val scope = definition.scopeName ?: return
        if (!loaded.add(scope)) return
        if (depth < MAX_EMBED_DEPTH) {
            for (embedded in definition.embeddedLanguages.values) {
                definitions[embedded.lowercase()]?.let { load(it, depth + 1) }
            }
        }
        GrammarRegistry.getInstance().loadGrammar(definition)
    }

    /**
     * 配色を [theme] に合わせる。
     *
     * TextMate の配色は**レジストリの「今のテーマ」に追従する**ので、
     * [TextMateColorScheme] を作り直すのは1回だけでいい（作り直すと
     * エディタへの取り付け直しが要り、`onChangeTheme` の購読も増える）。
     *
     * 文法もテーマも読めなかったときのために、素の [EditorColorScheme] へ落ちる道を残す。
     */
    fun colorScheme(context: Context, current: EditorColorScheme?, theme: EditorTheme): EditorColorScheme {
        ensure(context)
        val name = themes.getValue(theme)
        val dark = theme.isDark
        val applied = ThemeRegistry.getInstance().setTheme(name)
        if (!applied) {
            Log.w(TAG, "theme not registered: $name")
            // 同梱テーマが読めていない状態。色より先に直すものがあるので、
            // ここは明暗だけ合った Sora 同梱のスキームで凌ぐ。
            return current ?: if (dark) SchemeDarcula() else EditorColorScheme()
        }
        if (current is TextMateColorScheme) return current
        return TextMateColorScheme.create(ThemeRegistry.getInstance())
    }

    /**
     * 配色の `colors` にある VS Code 名の色のうち、Sora が自分では読まないものを Sora の配色へ写す。
     * 配色を入れるたびに呼ぶ（Sora は配色を読み直すたびに自分の分を初期値へ戻す）。
     *
     * | json のキー | 写す先 |
     * |---|---|
     * | `editorBracketMatch.background` / `.border` / `.foreground` | 括弧の組の強調の 地 / 枠 / 文字色 |
     * | `editorStickyScroll.border` | 固定見出しの下の区切り |
     *
     * キーが無い・読めない色は触らない。
     *
     * @return 組の線の色（`editorBracketPairGuide.activeBackground1`）。無ければ 0（線を描かない）
     */
    fun applyKiwaColors(scheme: EditorColorScheme): Int {
        val colors = ((scheme as? TextMateColorScheme)?.rawTheme as? RawTheme)?.get("colors") as? RawTheme ?: return 0
        fun color(key: String): Int? = (colors.get(key) as? String)
            ?.let { runCatching { ColorUtils.parseRGBAToARGB(it) }.getOrNull() }
        color("editorBracketMatch.background")?.let { scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, it) }
        color("editorBracketMatch.border")?.let { scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BORDER, it) }
        color("editorBracketMatch.foreground")?.let { scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, it) }
        color("editorStickyScroll.border")?.let { scheme.setColor(EditorColorScheme.STICKY_SCROLL_DIVIDER, it) }
        return color("editorBracketPairGuide.activeBackground1") ?: 0
    }
}
