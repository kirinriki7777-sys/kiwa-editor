package dev.kirin.kiwa.engine.sora

import android.content.Context
import android.graphics.Typeface
import android.view.View
import dev.kirin.editoradapter.EditorDocument
import dev.kirin.editoradapter.EditorEngine
import dev.kirin.editoradapter.EditorSearch
import dev.kirin.editoradapter.EditorTheme
import dev.kirin.editoradapter.ImeBoundary
import dev.kirin.editoradapter.TextPosition
import dev.kirin.editoradapter.TextRange
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * [EditorEngine] の Sora 実装。
 *
 * 中身はほぼ委譲。**この層に意味があるのは、Sora の型がここから外へ出ないこと**で、
 * ロジックそのものは持たない。IME まわりだけは [SoraImeBoundary] が引き受ける。
 */
internal class SoraEditorEngine(context: Context, trace: ImeTrace?) : EditorEngine {

    /** 文法とテーマを assets から読むのに要る。Activity を握らないよう application の方を持つ。 */
    private val context = context.applicationContext

    private val editor = KiwaCodeEditor(context, trace)
    private val boundary = SoraImeBoundary(editor, trace)
    private val searcher = SoraSearch(editor)

    /**
     * インデントの設定を**このクラスが覚えている**。
     *
     * 字下げを生成するのは言語オブジェクトの側（`Language.useTab()` / `TextMateLanguage.setTabSize`）で、
     * [setLanguage] は言語を作り直す ── そのたびに既定（スペース幅4）へ戻ってしまう。
     * 呼び出し側に「言語を替えたらインデントを入れ直す」を強いると、
     * 呼び忘れが「設定したのに反映されない」として出る。**復元はここでやる。**
     */
    private var indentWidth = 4
    private var indentUsesTab = false

    init {
        editor.attachBoundary(boundary)
    }

    override fun asView(): View = editor

    override fun getText(): CharSequence = editor.text

    override fun setText(text: CharSequence) {
        // 直後にプログラムから編集してはいけない ── LineBreakLayout.afterInsert（:186）が
        // まだ空の内部リストを参照して落ちる。1フレーム空ければ通る（S3b で実機確認）。
        // 契約は EditorEngine.setText の Javadoc 側に書いてある。
        //
        // **出ている文書の中身を丸ごと差し替える**（履歴も位置も消える）。タブの札は生きたまま ──
        // ここで `editor.setText(text)` を直に呼ぶと Sora が別の Content を作り、
        // [shown] が指している文書と画面が食い違う。
        val doc = shown
        if (doc == null) {
            showDocument(newDocument(text))
            return
        }
        // **中身が別物になるので検索を捨てる**（理由は showDocument と同じ）。
        searcher.stop()
        doc.content = Content(text)
        doc.scrollX = 0
        doc.scrollY = 0
        editor.setText(doc.content, true, null)
    }

    // ------------------------------------------------------------------
    // 文書（タブ）
    // ------------------------------------------------------------------

    /**
     * 1つの文書。**Undo 履歴とカーソルは [Content] が持っている**ので、
     * タブを切り替えても消えないのはそのおかげ（`text/Content.java:70` が `UndoManager`、
     * `widget/CodeEditor.java:4002` が `cursor = this.text.getCursor()`）。
     *
     * スクロール位置だけは [Content] の外＝ビュー側にあるので、ここで覚える。
     */
    private class SoraDocument(var content: Content) : EditorDocument {
        var scrollX = 0
        var scrollY = 0
    }

    private var shown: SoraDocument? = null

    override fun newDocument(text: CharSequence): EditorDocument = SoraDocument(Content(text))

    override fun showDocument(document: EditorDocument) {
        val next = document as SoraDocument
        val previous = shown
        if (previous === next) return
        // **本文が別物になるので検索を捨てる。** 一致の位置は前の Content の中の数字で、
        // そのまま持ち越すと別の場所を指す ── エンジンは中身が替わったことに気づかない。
        // 捨てたことは `PublishSearchResultEvent` で呼び出し側へ届く（`stopSearch` も出す）ので、
        // 検索欄が開いていれば新しい本文で引き直せる。
        searcher.stop()
        if (previous != null) {
            // **ビューの実際の位置を採る。** `scroller.currX/currY` は勢いを付けて流している
            // 途中の値なので、指を離した直後だと2行ぶんずれる（実機で実測: 357 行目で保存して
            // 359 行目で戻った）。`setEditorOffsets()` がビューへ書き込む先がここ。
            previous.scrollX = editor.scrollX
            previous.scrollY = editor.scrollY
        }
        // **`reuseContentObject = true` が肝**（`widget/CodeEditor.java:3982`）。
        // false だと Sora が `new Content(text)` を作り直し、履歴もカーソルも別物になる。
        // 差し替えは IME をつなぎ直さない口を通す ── つなぎ直すとタブを替えるたびに全角へ戻る。
        editor.switchContent(next.content)
        shown = next
        // setText が `createLayout()` を呼び直すので、**その場で戻すと座標がまだ無い**。
        // 1フレーム空けてから戻す ── S3b（setText 直後に触ると落ちる）と同じ事情。
        editor.post {
            if (shown === next) {
                editor.scroller.startScroll(next.scrollX, next.scrollY, 0, 0, 0)
                editor.scroller.abortAnimation()
                editor.invalidate()
            }
        }
    }

    override fun setOnChangeListener(listener: Runnable?) {
        editor.setChangeListener(listener)
    }

    override fun forgetDocument(document: EditorDocument) {
        val doc = document as SoraDocument
        if (shown === doc) shown = null
        // Content は GC に任せる。**明示的に捨てる口は Sora に無い**
        // （`Content` はリスナーを Editor 側に登録されるだけで、外している先は setText が持つ）。
    }

    override fun positionOf(index: Int): TextPosition {
        val content = editor.text
        val clamped = index.coerceIn(0, content.length)
        val position = content.indexer.getCharPosition(clamped)
        return TextPosition(position.line, position.column, position.index)
    }

    /**
     * 範囲を置き換える。**バッチで挟んで Undo の1単位にする。**
     *
     * 挟まないと `Content` が削除と挿入を別々の操作として履歴へ積むので、
     * 「行を1つ上へ」を戻すのに2回押すことになる（`text/Content.java` の `UndoManager`）。
     */
    override fun replaceRange(range: TextRange, replacement: CharSequence) {
        val content = editor.text
        val length = content.length
        val startIndex = minOf(range.start().index(), range.end().index()).coerceIn(0, length)
        val endIndex = maxOf(range.start().index(), range.end().index()).coerceIn(0, length)
        val start = content.indexer.getCharPosition(startIndex)
        val end = content.indexer.getCharPosition(endIndex)
        content.beginBatchEdit()
        try {
            if (startIndex != endIndex) {
                content.replace(start.line, start.column, end.line, end.column, replacement)
            } else if (replacement.isNotEmpty()) {
                content.insert(start.line, start.column, replacement)
            }
        } finally {
            content.endBatchEdit()
        }
    }

    /**
     * 物理キーの文字と同じ口を通す（`widget/EditorKeyEventHandler.java:440` が
     * `commitText(text)` のあと `notifyIMEExternalCursorChange()` を呼ぶのと同じ並び）。
     * 括弧の組は `commitText` の中でバッチに挟まれるので、Undo は1回で戻る
     * （`widget/CodeEditor.java:2097`、選択を囲む時は `:2072`）。
     */
    override fun typeText(text: CharSequence) {
        if (text.isEmpty()) return
        editor.commitText(text)
        editor.notifyIMEExternalCursorChange()
    }

    override fun setLineSeparator(separator: String) {
        // widget/CodeEditor.java:2634。既定は LF なので、CRLF のファイルを開いたら
        // ここを合わせないと足した行だけ改行コードが変わる。
        editor.lineSeparator = when (separator) {
            "\r\n" -> LineSeparator.CRLF
            "\r" -> LineSeparator.CR
            else -> LineSeparator.LF
        }
    }

    override fun getSelection(): TextRange {
        val cursor = editor.cursor
        return TextRange(
            TextPosition(cursor.leftLine, cursor.leftColumn, cursor.left),
            TextPosition(cursor.rightLine, cursor.rightColumn, cursor.right)
        )
    }

    override fun setSelection(range: TextRange) {
        // 権威は index 側に置く。呼び出し側が行・桁を埋めずに範囲を組み立てても壊れないように、
        // 行・桁はここで引き直す。
        val content = editor.text
        val length = content.length
        val startIndex = range.start().index().coerceIn(0, length)
        val endIndex = range.end().index().coerceIn(0, length)
        val start = content.indexer.getCharPosition(minOf(startIndex, endIndex))
        val end = content.indexer.getCharPosition(maxOf(startIndex, endIndex))
        if (start.index == end.index) {
            editor.setSelection(start.line, start.column)
        } else {
            editor.setSelectionRegion(start.line, start.column, end.line, end.column)
        }
    }

    override fun canUndo(): Boolean = editor.canUndo()

    override fun undo() = editor.undo()

    override fun canRedo(): Boolean = editor.canRedo()

    override fun redo() = editor.redo()

    override fun setTheme(theme: EditorTheme) {
        // 配色は TextMate のテーマから作る。構文の色とエディタの色を1枚のテーマで揃えたいので、
        // Sora 同梱のスキームは使わない（片方だけ変えると、暗い地に明るい構文色が残る）。
        val scheme = TextMateSetup.colorScheme(context, editor.colorScheme, theme)
        // 同じ実体なら入れ直さない ── setColorScheme は detach → attach をするので、
        // テーマの購読を毎回外して付け直すことになる（widget/CodeEditor.java:4178）。
        if (scheme !== editor.colorScheme) {
            editor.colorScheme = scheme
        } else {
            editor.invalidate()
        }
        // **行番号と本文の間に線を引く**（見た目案 01 の `│`）。TextMate の配色は
        // この線を透明にしている（langs/textmate/TextMateColorScheme.java:127）ので、
        // 配色を入れるたびに字下げの線と同じ色で塗り直す。
        editor.colorScheme.setColor(
            EditorColorScheme.LINE_DIVIDER,
            editor.colorScheme.getColor(EditorColorScheme.BLOCK_LINE)
        )
        // 括弧の組の強調・固定見出しの区切りは Sora が読まない名前なので、ここで写す。
        editor.bracketPairGuideColor = TextMateSetup.applyKiwaColors(editor.colorScheme)
        // 変換中の装飾に必要な濃さは地の明るさで変わるので、境界にも知らせる。
        boundary.setTheme(theme)
    }

    override fun setLanguage(language: String?) {
        // 知らない名前は EmptyLanguage が返るので、色が付かないだけで開けなくはならない。
        editor.setEditorLanguage(TextMateSetup.language(context, language))
        // 新しい言語オブジェクトは字下げの既定を持っている（スペース幅4）。
        // 設定を入れ直さないと、ファイルを開いた瞬間だけインデントが戻る。
        applyIndentToLanguage()
    }

    override fun setTextSize(sp: Float) {
        // widget/CodeEditor.java:4033 が sp → px を端末の密度で換算する。
        // ここで px を計算しない ── 端末を替えたときに合わなくなるのはそのやり方。
        editor.setTextSize(sp)
    }

    override fun setFont(name: String?) {
        val typeface = when (name?.lowercase()) {
            "sans" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            // 知らない名前は等幅へ落とす。コードエディタなので迷ったらこちらが安全。
            else -> Typeface.MONOSPACE
        }
        editor.typefaceText = typeface
        // 行番号も同じ書体にする。片方だけ替えると桁がずれて数字が揺れる。
        editor.typefaceLineNumber = typeface
    }

    override fun setIndent(width: Int, useTab: Boolean) {
        // 0 以下だと字下げが消え、大きすぎると1段で画面が埋まる。境界で潰す。
        indentWidth = width.coerceIn(1, 16)
        indentUsesTab = useTab
        // タブ文字の表示幅でもある（widget/CodeEditor.java:2556）。
        editor.tabWidth = indentWidth
        applyIndentToLanguage()
    }

    /**
     * 字下げの設定を今の言語オブジェクトへ入れる。
     *
     * TextMate 以外の言語（＝ [TextMateSetup.language] が `EmptyLanguage` を返したとき）は
     * `useTab()` が固定なので何もしない ── 色が付かないファイルは字下げも既定のまま。
     */
    private fun applyIndentToLanguage() {
        val language = editor.editorLanguage as? TextMateLanguage ?: return
        language.setTabSize(indentWidth)
        language.useTab(indentUsesTab)
    }

    override fun setLineNumbersVisible(visible: Boolean) {
        editor.isLineNumberEnabled = visible
    }

    override fun setShowInvisibles(visible: Boolean) {
        // 「見せる」なら空白は全種類＋改行まで出す。行頭だけ出す形は、
        // 行末の空白（差分の原因になる方）が見えないので目的を果たさない。
        editor.nonPrintablePaintingFlags = if (visible) {
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
                CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
                CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        } else {
            0
        }
    }

    override fun setHighlightCurrentLine(enabled: Boolean) {
        editor.isHighlightCurrentLine = enabled
    }

    override fun setWordWrap(enabled: Boolean) {
        // 有効にすると先頭への1文字挿入が行数に比例して遅くなる
        // （実機実測: 1,000行 3.7ms / 5,000行 13.1ms / 20,000行 56.1ms = 1フレームの3.4倍）。
        // 行数で切る判断は呼び出し側の責務。ここは言われたとおりに設定するだけ。
        editor.setWordwrap(enabled)
    }

    override fun setLineSpacing(multiplier: Float) {
        // 1 未満にすると行が重なる。上は 3 倍で止める（それ以上は1画面に数行しか残らない）。
        editor.lineSpacingMultiplier = multiplier.coerceIn(1f, 3f)
    }

    override fun setCursorBlink(enabled: Boolean) {
        // 0 以下で「点滅しない（出しっぱなし）」（widget/CodeEditor.java:815）。
        editor.setCursorBlinkPeriod(if (enabled) CodeEditor.DEFAULT_CURSOR_BLINK_PERIOD else 0)
    }

    override fun setAutoClosePairs(enabled: Boolean) {
        // 空の組を一緒に消す側（KiwaCodeEditor.deleteEmptyBracketPair）も同じ値を見ている。
        editor.props.symbolPairAutoCompletion = enabled
    }

    override fun setAutoIndent(enabled: Boolean) {
        editor.props.autoIndent = enabled
    }

    override fun setHighlightMatchingBrackets(enabled: Boolean) {
        // Sora は2系統持っている ── 言語の括弧の組を見る方（setHighlightBracketPair）と、
        // 描画の側で区切り文字を見る方（props.highlightMatchingDelimiters）。片方だけ切ると残る。
        editor.isHighlightBracketPair = enabled
        editor.props.highlightMatchingDelimiters = enabled
        editor.invalidate()
    }

    override fun setIndentGuides(enabled: Boolean) {
        editor.isBlockLineEnabled = enabled
    }

    override fun setStickyScroll(enabled: Boolean) {
        // 折り返し中は Sora が出さない（EditorRenderer.getStuckCodeBlocks の最初の条件）。
        editor.props.stickyScroll = enabled
        editor.invalidate()
    }

    override fun setPinLineNumbers(pinned: Boolean) {
        editor.setPinLineNumber(pinned)
    }

    override fun ime(): ImeBoundary = boundary

    /** **同じ実体を返し続ける** ── 登録されたリスナーが黙って消えないように。 */
    override fun search(): EditorSearch = searcher
}
