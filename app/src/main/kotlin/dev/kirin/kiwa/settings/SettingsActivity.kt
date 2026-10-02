package dev.kirin.kiwa.settings

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.kirin.kiwa.BuildConfig
import dev.kirin.kiwa.file.StorageAccess
import dev.kirin.kiwa.symbols.SymbolKeys
import dev.kirin.kiwa.ui.Palette
import dev.kirin.kiwa.ui.ThemeChoice
import dev.kirin.kiwa.ui.TitledFrame
import java.io.IOException

/**
 * 設定画面。**別 Activity にしてある。**
 *
 * エディタが居ない画面なので IME と焦点の取り合いが起きない ──
 * 本体で Compose を避けた理由（Sora が View で、IME との間に層を増やしたくない）が
 * ここには当てはまらない。とはいえ Compose を足すと依存が1式増えるので、
 * **画面1枚のためには入れない**。組み方は本体と同じ View ＋ コード。
 *
 * ## 変更の伝え方
 *
 * この画面は [SettingsStore] へ書くだけで、本体へ結果を返さない。
 * 本体は `onResume` で読み直し、**前と違っていたときだけ**適用し直す。
 * 結果コードで受け渡すと「どの項目が変わったか」を数え始めることになり、
 * 項目を足すたびに配線が増える。
 *
 * ## 形（見た目案 01。2026-09-29）
 *
 * **左に分類、右にその分類の項目**を、それぞれ見出し付きの枠で囲む。前は全項目を
 * 1本の縦長の一覧に並べていて、下の方の項目（ファイル・キー）はスクロールしないと見えなかった。
 * 分類は [rebuild] が `section(...)` を呼んだ順にそのまま並ぶ ── 分類の一覧を別に持たない。
 */
class SettingsActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var settings: EditorSettings
    private lateinit var palette: Palette
    private lateinit var content: LinearLayout
    private lateinit var root: LinearLayout

    /** 左の枠（分類）と右の枠（選んだ分類の項目）。 */
    private lateinit var categoryFrame: TitledFrame
    private lateinit var itemFrame: TitledFrame
    private lateinit var categoryList: LinearLayout

    /** 今見ている分類。[rebuild] の `section(...)` の名前で指す。 */
    private var selected = "表示"

    /** [rebuild] が通った分類の名前。左の一覧はこれを並べる。 */
    private val categories = ArrayList<String>()

    /** 今 [rebuild] が組んでいる項目が、選んだ分類のものか。違えば画面に足さない。 */
    private var including = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        settings = store.load()
        palette = settings.theme.resolve(this)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD * 2, PAD, PAD * 2, PAD * 3)
        }
        categoryList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, PAD / 2, 0, PAD / 2)
        }
        categoryFrame = TitledFrame(this).apply {
            setTitle("分類")
            setContent(ScrollView(this@SettingsActivity).apply { addView(categoryList) })
        }
        itemFrame = TitledFrame(this).apply {
            setContent(ScrollView(this@SettingsActivity).apply { addView(content) })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(PAD / 2, PAD, PAD / 2, PAD / 2)
        }
        val density = resources.displayMetrics.density
        body.addView(categoryFrame, LinearLayout.LayoutParams((CATEGORY_WIDTH_DP * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(itemFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = PAD / 2
        })
        root.addView(header())
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        palette.applyTo(root)
        palette.applySystemBars(this)
        applyInsets(root)
        rebuild()
    }

    /** 本体と同じ。入れないとツールバーがステータスバーの下へ潜り、タッチが届かない帯ができる。 */
    private fun applyInsets(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun header(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PAD, PAD / 2, PAD, PAD / 2)
            setBackgroundColor(palette.toolbar)
        }
        bar.addView(
            Button(this).apply {
                text = "戻る"
                isAllCaps = false
                setBackgroundColor(palette.button)
                setTextColor(palette.text)
                setOnClickListener { finish() }
            }
        )
        bar.addView(
            TextView(this).apply {
                text = "設定"
                textSize = 16f
                setTextColor(palette.text)
                setPadding(PAD, 0, 0, 0)
            }
        )
        return bar
    }

    // ------------------------------------------------------------------
    // 画面の中身
    // ------------------------------------------------------------------

    /**
     * 全部作り直す。
     *
     * 1項目だけ差分更新すると「配色を変えたのに周りの色が古いまま」のような取りこぼしが出る。
     * 数十行の画面なので、**変えたら全部組み直す**方が安い。
     */
    private fun rebuild() {
        palette = settings.theme.resolve(this)
        palette.applyTo(root)
        palette.applySystemBars(this)
        content.removeAllViews()
        categories.clear()
        including = false

        section("表示")
        // **配色はここの先頭。** 2026-10-02 に「配色」の節を畳んだ ── 1項目だけの節は、
        // 見出しを探す手間のほうが中身より大きい。見た目を変える項目は全部この節に集める。
        pick("配色", settings.theme.label) {
            askChoice("配色", ThemeChoice.entries.map { it.label }) { index ->
                update(settings.copy(theme = ThemeChoice.entries[index]))
            }
        }
        number("文字の大きさ", "${settings.fontSizeSp.toInt()} sp") {
            askNumber("文字の大きさ（sp）", settings.fontSizeSp.toInt(), 6, 48) {
                update(settings.copy(fontSizeSp = it.toFloat()))
            }
        }
        pick("書体", EditorSettings.FONTS.firstOrNull { it.first == settings.font }?.second ?: settings.font) {
            askChoice("書体", EditorSettings.FONTS.map { it.second }) { index ->
                update(settings.copy(font = EditorSettings.FONTS[index].first))
            }
        }
        pick("行の高さ", spacingLabel(settings.lineSpacing)) {
            askChoice("行の高さ", EditorSettings.LINE_SPACINGS.map { spacingLabel(it) }) { index ->
                update(settings.copy(lineSpacing = EditorSettings.LINE_SPACINGS[index]))
            }
        }
        toggle("行番号を出す", null, settings.showLineNumbers) {
            update(settings.copy(showLineNumbers = it))
        }
        toggle("行番号を左端に留める", "横へスクロールしても行番号が流れない", settings.pinLineNumbers) {
            update(settings.copy(pinLineNumbers = it))
        }
        toggle("空白と改行を見せる", "行末の空白が見える。差分の原因を探すとき用", settings.showInvisibles) {
            update(settings.copy(showInvisibles = it))
        }
        toggle("カーソルの行に地を敷く", null, settings.highlightCurrentLine) {
            update(settings.copy(highlightCurrentLine = it))
        }
        toggle("対応する括弧を強調する", null, settings.highlightMatchingBrackets) {
            update(settings.copy(highlightMatchingBrackets = it))
        }
        toggle(
            "インデントガイドを出す",
            "字下げの段に縦線を引く。線が出るのはブロックの範囲を文法が教えてくれる言語だけ",
            settings.indentGuides
        ) {
            update(settings.copy(indentGuides = it))
        }
        toggle("カーソルを点滅させる", null, settings.cursorBlink) {
            update(settings.copy(cursorBlink = it))
        }
        toggle("折り返す", null, settings.wordWrap) {
            update(settings.copy(wordWrap = it))
        }
        number(
            "折り返しを切る行数",
            "${settings.wrapLineLimit} 行",
            "これを超えるファイルでは折り返さない。実機で 20,000 行のとき" +
                "1文字打つのに 56ms 掛かった（1フレームの3.4倍）"
        ) {
            askNumber("折り返しを切る行数", settings.wrapLineLimit, 1, 1_000_000) {
                update(settings.copy(wrapLineLimit = it))
            }
        }
        toggle("全画面（システムバーを隠す）", null, settings.fullscreen) {
            update(settings.copy(fullscreen = it))
        }

        section("編集")
        number("インデントの幅", "${settings.indentWidth} 桁") {
            askNumber("インデントの幅（桁）", settings.indentWidth, 1, 16) {
                update(settings.copy(indentWidth = it))
            }
        }
        pick("インデントの文字", if (settings.indentUsesTab) "タブ" else "スペース") {
            askChoice("インデントの文字", listOf("スペース", "タブ")) { index ->
                update(settings.copy(indentUsesTab = index == 1))
            }
        }
        toggle("改行で字下げを引き継ぐ", null, settings.autoIndent) {
            update(settings.copy(autoIndent = it))
        }
        toggle(
            "括弧を自動で閉じる",
            "どの組を閉じるかは言語が決める。切ると、空の組を Backspace で一緒に消す動きも止まる",
            settings.autoClosePairs
        ) {
            update(settings.copy(autoClosePairs = it))
        }

        section("保存")
        toggle(
            "画面を離れるとき保存する",
            "時間では保存しない。打っている途中の中身が母艦へ同期されるのを避けるため",
            settings.autoSave
        ) {
            update(settings.copy(autoSave = it))
        }
        toggle("行末の空白を消す", null, settings.trimTrailingWhitespace) {
            update(settings.copy(trimTrailingWhitespace = it))
        }
        toggle("最後の行を改行で終える", null, settings.insertFinalNewline) {
            update(settings.copy(insertFinalNewline = it))
        }
        note(
            "上の2つは保存するときに本文を直す（1回の「戻す」で直す前へ戻れる）。" +
                "入れると、開いて保存しただけのファイルにも差分が出ることがある。変換中の保存では直さない"
        )

        section("言語別")
        note("載せた言語だけ、インデントを上書きする。載っていない言語は上の既定に従う")
        for ((language, rules) in settings.perLanguage) {
            val width = rules.indentWidth?.let { "$it 桁" } ?: "既定"
            val kind = rules.indentUsesTab?.let { if (it) "タブ" else "スペース" } ?: "既定"
            pick(language, "$width / $kind") { editLanguage(language) }
        }
        action("言語を足す") { addLanguage() }
        note("拡張子 → 言語の追加（組み込みの表より先に引く）")
        for ((extension, language) in settings.extraExtensions) {
            pick(".$extension", language) { editExtension(extension) }
        }
        action("拡張子を足す") { addExtension() }

        section("ファイル")
        toggle("隠しファイルを出す", null, settings.showHiddenFiles) {
            update(settings.copy(showHiddenFiles = it))
        }
        pick("並び順", settings.fileSort.label) {
            askChoice("並び順", EditorSettings.FileSort.entries.map { it.label }) { index ->
                update(settings.copy(fileSort = EditorSettings.FileSort.entries[index]))
            }
        }
        pick("文字コードの既定", settings.defaultCharset) {
            askChoice("文字コードの既定", EditorSettings.CHARSETS) { index ->
                update(settings.copy(defaultCharset = EditorSettings.CHARSETS[index]))
            }
        }
        note(
            "既に在るファイルの文字コードは判別した結果を使う（設定では上書きしない）。" +
                "これが効くのは、どの候補でも往復してしまうファイル ── つまり ASCII だけのものに何を名乗らせるか"
        )
        pick(
            "改行コードの既定",
            EditorSettings.LINE_SEPARATORS.firstOrNull { it.first == settings.defaultLineSeparator }?.second
                ?: "LF (Unix)"
        ) {
            askChoice("改行コードの既定", EditorSettings.LINE_SEPARATORS.map { it.second }) { index ->
                update(settings.copy(defaultLineSeparator = EditorSettings.LINE_SEPARATORS[index].first))
            }
        }
        note("改行が1つも無いファイルに行を足すときだけ使う。改行が在るファイルはそちらの多数派に合わせる")
        number("開けるファイルの上限", "${settings.maxOpenMegabytes} MB") {
            askNumber("開けるファイルの上限（MB）", settings.maxOpenMegabytes, 1, 512) {
                update(settings.copy(maxOpenMegabytes = it))
            }
        }
        toggle("最後に開いたフォルダを覚える", null, settings.rememberLastDirectory) {
            update(settings.copy(rememberLastDirectory = it))
        }
        action(if (StorageAccess.hasAccess()) "ファイルへのアクセス: 許可されている" else "ファイルへのアクセスを許可する") {
            StorageAccess.promptFor(this)
        }

        section("キー")
        toggle(
            "記号キー列を出す",
            "ソフトキーボードが出ている間だけ出る。物理キーボードのときは本物の Tab / Esc / 矢印がある",
            settings.showSymbolRow
        ) {
            update(settings.copy(showSymbolRow = it))
        }
        pick("列に出すキー", "${settings.symbolRowKeys.size} 個") { pickSymbolKeys() }
        note(
            "並びは設定のファイルが持っているが、画面から順番は変えられない ── " +
                "ここで選んだものは決まった順（修飾 → 生キー → コマンド → 記号）で並ぶ"
        )
        note(
            "キーバインドはまだ変えられない。Ctrl+Space はこの端末ではアプリまで届かず、" +
                "IME（Gboard）が自分の入力方式の切り替えに使っている ── " +
                "実機で Ctrl+ の34通りを撃って確かめた"
        )

        section("このアプリについて")
        add(row("版", BuildConfig.VERSION_NAME, null))
        action("ライセンス") { askLicense() }
        renderCategories()
    }

    /** 左の分類の一覧を描き直し、枠に色を入れる。 */
    private fun renderCategories() {
        categoryList.removeAllViews()
        for (name in categories) {
            val chosen = name == selected
            categoryList.addView(TextView(this).apply {
                text = if (chosen) "▶ $name" else "   $name"
                textSize = 15f
                setTextColor(if (chosen) palette.text else palette.dim)
                setBackgroundColor(if (chosen) palette.button else palette.background)
                setPadding(PAD * 2, PAD, PAD, PAD)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selected = name
                    rebuild()
                }
            })
        }
        itemFrame.setTitle(selected)
        categoryFrame.apply(palette, emphasized = false)
        itemFrame.apply(palette, emphasized = true)
    }

    /** 項目を画面に足す。**選んだ分類のものだけ。** */
    private fun add(view: View) {
        if (including) content.addView(view)
    }

    private fun spacingLabel(value: Float): String =
        if (value == 1.0f) "1.0（詰める）" else String.format(java.util.Locale.ROOT, "%.1f", value)

    private fun update(next: EditorSettings) {
        settings = next
        store.save(next)
        rebuild()
    }

    // ------------------------------------------------------------------
    // 言語別
    // ------------------------------------------------------------------

    private fun addLanguage() {
        askText("言語名", "kotlin", asciiOnly = true) { name ->
            val key = Identifier.normalize(name) ?: return@askText rejectIdentifier(name)
            update(
                settings.copy(
                    perLanguage = settings.perLanguage + (key to EditorSettings.LanguageRules(
                        indentWidth = settings.indentWidth,
                        indentUsesTab = settings.indentUsesTab
                    ))
                )
            )
        }
    }

    private fun editLanguage(language: String) {
        val rules = settings.perLanguage[language] ?: return
        AlertDialog.Builder(this)
            .setTitle(language)
            .setItems(arrayOf("インデントの幅", "インデントの文字", "この上書きを消す")) { _, index ->
                when (index) {
                    0 -> askNumber("$language のインデント幅", rules.indentWidth ?: settings.indentWidth, 1, 16) {
                        putLanguage(language, rules.copy(indentWidth = it))
                    }
                    1 -> askChoice("$language のインデント", listOf("スペース", "タブ")) {
                        putLanguage(language, rules.copy(indentUsesTab = it == 1))
                    }
                    2 -> update(settings.copy(perLanguage = settings.perLanguage - language))
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun putLanguage(language: String, rules: EditorSettings.LanguageRules) {
        update(settings.copy(perLanguage = settings.perLanguage + (language to rules)))
    }

    private fun addExtension() {
        askText("拡張子（ドット無し）", "gradle", asciiOnly = true) { extension ->
            val key = Identifier.normalize(extension)?.removePrefix(".")?.takeIf { it.isNotEmpty() }
                ?: return@askText rejectIdentifier(extension)
            askText("この拡張子を塗る言語名", "groovy", asciiOnly = true) { language ->
                val value = Identifier.normalize(language) ?: return@askText rejectIdentifier(language)
                update(settings.copy(extraExtensions = settings.extraExtensions + (key to value)))
            }
        }
    }

    private fun editExtension(extension: String) {
        AlertDialog.Builder(this)
            .setTitle(".$extension")
            .setItems(arrayOf("言語を変える", "この対応を消す")) { _, index ->
                when (index) {
                    0 -> askText(
                        "言語名",
                        settings.extraExtensions[extension] ?: "",
                        asciiOnly = true
                    ) { language ->
                        val value = Identifier.normalize(language)
                        if (value == null) {
                            rejectIdentifier(language)
                        } else {
                            update(settings.copy(extraExtensions = settings.extraExtensions + (extension to value)))
                        }
                    }
                    1 -> update(settings.copy(extraExtensions = settings.extraExtensions - extension))
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 行の部品
    // ------------------------------------------------------------------

    /**
     * 分類の始まり。**ここから次の `section` までの項目がこの分類に入る。**
     * 見出しは右の枠の上に出るので、ここでは行を足さない。
     */
    private fun section(title: String) {
        categories.add(title)
        including = title == selected
    }

    private fun note(text: String) {
        add(
            TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(palette.dim)
                setPadding(0, 0, 0, PAD)
            }
        )
    }

    private fun toggle(title: String, note: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val row = row(title, null, note)
        @Suppress("DEPRECATION")
        val switch = Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(switch)
        // **行全体を当たり判定にする。** つまみだけだと幅 94px ＝ 画面の 3.7% しか反応せず、
        // 指では素通りする（2026-09-06 の実機で実際に外した）。
        // `toggle()` は `switch` 側の変化を聞いているので、ここは値を反転させるだけでよい。
        row.setOnClickListener { switch.isChecked = !switch.isChecked }
        add(row)
    }

    private fun pick(title: String, value: String, onClick: () -> Unit) {
        val row = row(title, value, null)
        row.setOnClickListener { onClick() }
        add(row)
    }

    private fun number(title: String, value: String, note: String? = null, onClick: () -> Unit) {
        val row = row(title, value, note)
        row.setOnClickListener { onClick() }
        add(row)
    }

    private fun action(title: String, onClick: () -> Unit) {
        val row = row(title, null, null)
        row.setOnClickListener { onClick() }
        add(row)
    }

    private fun row(title: String, value: String?, note: String?): LinearLayout {
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(palette.text)
            }
        )
        if (note != null) {
            labels.addView(
                TextView(this).apply {
                    text = note
                    textSize = 11f
                    setTextColor(palette.dim)
                }
            )
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, PAD, 0, PAD)
            isClickable = true
        }
        line.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            line.addView(
                TextView(this).apply {
                    text = value
                    textSize = 14f
                    setTextColor(palette.dim)
                }
            )
        }
        return line
    }

    // ------------------------------------------------------------------
    // 入力
    // ------------------------------------------------------------------

    /**
     * 記号キー列に出すキーを選ぶ。
     *
     * **選ぶのは「どれを出すか」だけで、順番は決まった順に入る**（[SymbolKeys.inCanonicalOrder]）。
     * 順番まで画面で並べ替えられるようにすると、22個を指で入れ替える UI が要る ──
     * そこまで要るかが分かっていないので、まず出し入れだけにしてある。
     * 設定のファイルを直に書けば任意の順番にできる（型は並びを持っている）。
     */
    private fun pickSymbolKeys() {
        val all = SymbolKeys.ALL
        // **種類も出す。** `{` と `Tab` と `保存` が同じ見た目で並ぶと、
        // 「これは文字か操作か」が押すまで分からない。
        val labels = all.map { key -> "${key.label}　　${key.kind.label}" }
        val checked = BooleanArray(all.size) { all[it].id in settings.symbolRowKeys }
        AlertDialog.Builder(this)
            .setTitle("列に出すキー")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, index, on -> checked[index] = on }
            .setPositiveButton("決定") { _, _ ->
                val chosen = all.filterIndexed { i, _ -> checked[i] }.map { it.id }
                update(settings.copy(symbolRowKeys = SymbolKeys.inCanonicalOrder(chosen)))
            }
            .setNeutralButton("既定へ戻す") { _, _ ->
                update(settings.copy(symbolRowKeys = SymbolKeys.DEFAULT))
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun askChoice(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, index -> onPick(index) }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 読む文書を選ばせる。文書は assets にある（LICENSE・NOTICE・third_party/）。 */
    private fun askLicense() {
        askChoice("ライセンス", LICENSE_DOCUMENTS.map { it.first }) { index ->
            val (title, path) = LICENSE_DOCUMENTS[index]
            showDocument(title, path)
        }
    }

    /** 文書の全文を、スクロールできるダイアログに等幅で出す。文字の大きさは既定のまま。 */
    private fun showDocument(title: String, path: String) {
        val text = try {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            "読めなかった: $path"
        }
        val body = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(PAD, PAD, PAD, PAD)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun askNumber(title: String, current: Int, min: Int, max: Int, onPick: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("$title（$min〜$max）")
            .setView(input)
            .setPositiveButton("決める") { _, _ ->
                // 範囲外は黙って端へ寄せる。入れ直しを求めるほどの入力ではない。
                input.text.toString().trim().toIntOrNull()?.let { onPick(it.coerceIn(min, max)) }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /**
     * 文字を打たせる。
     *
     * **識別子を入れる欄は IME に英数を頼む**（[asciiOnly]）── E7 の実機で、
     * 日本語 IME を通した `go` が「ご」として登録された。
     * ただし**頼みが効くかは IME 次第**なので、受け取った側でも
     * [Identifier] で寄せて範囲外を断る。殴るのと受け止めるのは別の仕事。
     */
    private fun askText(title: String, hint: String, asciiOnly: Boolean = false, onPick: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            if (asciiOnly) imeOptions = EditorInfo.IME_FLAG_FORCE_ASCII
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("決める") { _, _ -> onPick(input.text.toString()) }
            .setNegativeButton("やめる", null)
            .show()
    }

    /**
     * 受けられなかったと伝える。**黙って何も起きないのが一番困る** ──
     * E7 で登録された「ご」は、断られていれば打ち直せた。
     */
    private fun rejectIdentifier(raw: String) {
        val what = raw.trim()
        val message = if (what.isEmpty()) {
            "名前が要る"
        } else {
            "英数で打って（$what は使えない）"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 左の分類の枠の幅（dp）。 */
        const val CATEGORY_WIDTH_DP = 220

        const val PAD = 24

        /** 「ライセンス」で選べる文書（画面の名前, assets の中の道筋）。 */
        val LICENSE_DOCUMENTS = listOf(
            "Kiwa のライセンス（Apache-2.0）" to "LICENSE",
            "NOTICE" to "NOTICE",
            "第三者の一覧" to "third_party/THIRD_PARTY_NOTICES.md",
            "LGPL 2.1 以降（Sora Editor）" to "third_party/licenses/LGPL-2.1-or-later.txt",
            "EPL 2.0（Eclipse tm4e・jdt.annotation）" to "third_party/licenses/EPL-2.0.txt",
            "MIT ライセンス" to "third_party/licenses/MIT.txt",
            "TextMate bundle の許諾（Groovy・HTML）" to "third_party/licenses/TextMate-bundles.txt",
        )
    }
}
