package dev.kirin.kiwa.engine.sora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.lang.reflect.Field

/**
 * Sora の [CodeEditor] に、境界が握る必要のある口を開けただけのサブクラス。
 *
 * ここに置いたのは「`protected` なので継承しないと触れないもの」だけ。
 * ロジックは [SoraImeBoundary] と [ComposingBoundaryConnection] が持つ。
 */
internal class KiwaCodeEditor(
    context: Context,
    private val trace: ImeTrace?
) : CodeEditor(context) {

    private var boundary: SoraImeBoundary? = null

    /** 責務3。composing の間 false になる。 */
    private var keyBindingsEnabled = true

    /**
     * 変換中の装飾。**構文ハイライトと同じスパンの経路**へ載せてある（[ComposingOverlay]）。
     * `setHighlightTexts` を使っていた頃は、表示行が1行だと描かれなかった。
     */
    private val composingOverlay = ComposingOverlay()

    init {
        setEditorLanguage(EmptyLanguage())
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

        // 責務4 — 物理キーボードでも composing 経路を使わせる。
        // 既定 true のままだと onCreateInputConnection が EditorInfo.inputType に TYPE_NULL を
        // 入れ、IME は composing を使わず1文字ずつ直接確定する経路へ落ちる（実測⑧）。
        // IME は onStartInput の時点で経路を決めるので、最初のフォーカスより前に切る必要がある。
        setDisableSoftKbdIfHardKbdAvailable(false)

        // 責務3 の実体。canHandleKeyBinding の override だけでは修飾キー無しの Tab を止められない
        // ── isKeyBindingEvent が Shift/Alt/Ctrl のいずれかを要求するため
        // （widget/EditorKeyEventHandler.java:79）。素の Tab はキーバインドとして扱われないまま
        // indentOrCommitTab() へ届く。だから介入点1（EditorKeyEvent の消費）を主とし、
        // canHandleKeyBinding は二重の保険として併用する。
        subscribeEvent(EditorKeyEvent::class.java) { event, _ ->
            val blocked = !keyBindingsEnabled && event.canIntercept()
            if (blocked) {
                event.markAsConsumed()
            }
            trace?.event(
                "sora", "key.event",
                "type", event.eventType.name,
                "action", event.action,
                "keyCode", event.keyCode,
                "keyName", android.view.KeyEvent.keyCodeToString(event.keyCode),
                "ctrl", event.isCtrlPressed,
                "alt", event.isAltPressed,
                "shift", event.isShiftPressed,
                "composing", hasComposingText(),
                "blockedByBoundary", blocked
            )
        }

        // 行番号の左・行番号と区切り線の間・区切り線と本文の間に、それぞれ約 8dp（案 B）。
        // Sora の既定は 0 / 2dp / 2dp で、数字が画面の端と本文に貼り付いて見える。
        setLineNumberMarginLeft(dpUnit * GUTTER_MARGIN_DP)
        setDividerMargin(dpUnit * GUTTER_MARGIN_DP, dpUnit * GUTTER_MARGIN_DP)

        installTracing()
        installAutoClosedTracking()
    }

    /**
     * 計測ログの JSONL は `source` / `event` / `phase` / `call` / `action` の名前を固定する。
     * `tools/analyze-composing-paths.py` がこれらを使って呼び出しと編集内容を対応付ける。
     */
    private fun installTracing() {
        val trace = this.trace ?: return

        subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            val start = event.changeStart
            val end = event.changeEnd
            trace.event(
                "sora", "content.change",
                "action", event.action,
                "startIndex", start.index,
                "startLine", start.line,
                "startColumn", start.column,
                "endIndex", end.index,
                "endLine", end.line,
                "endColumn", end.column,
                "changedText", TraceText.preview(event.changedText),
                "undoRedo", event.isCausedByUndoManager,
                "composing", hasComposingText()
            )
        }

        subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            trace.event(
                "sora", "selection.change",
                "cause", event.cause,
                "leftIndex", event.left.index,
                "rightIndex", event.right.index,
                "selected", event.isSelected,
                "composing", hasComposingText()
            )
        }
    }

    fun attachBoundary(boundary: SoraImeBoundary) {
        this.boundary = boundary
    }

    /**
     * 「中身かカーソルが変わった」の合図を1つだけ外へ出す。
     *
     * 計測（[installTracing]）と同じイベントを見ているが、**あちらは debug ビルドだけ**で、
     * 状態表示は release でも要る。同じ購読に相乗りさせると trace が null の時に消える。
     */
    private var changeListener: Runnable? = null

    fun setChangeListener(listener: Runnable?) {
        if (changeListener == null && listener != null) {
            subscribeEvent(ContentChangeEvent::class.java) { _, _ -> changeListener?.run() }
            subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> changeListener?.run() }
        }
        changeListener = listener
    }

    // ------------------------------------------------------------------
    // 変換中の装飾（責務1）
    // ------------------------------------------------------------------

    /**
     * 変換中の装飾を差し替える。空の列を渡せば消える。
     *
     * 描き直しは `setStyles` に任せる ── **これが公開されている唯一の口**で、
     * 表示リストの作り直し（`invalidateRenderNodes`）と描画時刻の更新まで面倒を見てくれる
     * （widget/CodeEditor.java:4254）。`invalidate()` だけでは、
     * ハードウェア描画で行が表示リストに焼かれている場合に色が変わらない。
     */
    fun setComposingOverlay(ranges: List<ComposingOverlay.Range>) {
        composingOverlay.set(ranges)
        setStyles(styles)
    }

    fun clearComposingOverlay() {
        if (composingOverlay.isEmpty) return
        composingOverlay.clear()
        setStyles(styles)
    }

    /**
     * 解析器が渡してくるスパンを、装飾を重ねられる形に包む。
     *
     * **包むのは `spans` の差し替えだけで、[Styles] 自体は作り直さない。**
     * 解析器は同じ [Styles] を持ち続けて中身を更新するので
     * （lang/analysis/AsyncIncrementalAnalyzeManager.java:573 が `styles.spans.modify()` を呼ぶ）、
     * こちらが別の入れ物へ移すと更新の行き先が食い違う。
     *
     * 言語を付けていないと `styles` は null で来る。そのときも器だけ作って包む ──
     * **色が付かない状態でも変換中の装飾は要る**ので、ここで諦めると
     * 拡張子の分からないファイルだけ装飾が出ない、という分かりにくい形になる。
     */
    private fun wrap(styles: Styles?): Styles {
        val wrapped = styles ?: Styles()
        val spans = wrapped.spans
        if (spans !is ComposingOverlaySpans) {
            wrapped.spans = ComposingOverlaySpans(spans, composingOverlay) { line ->
                text.getColumnCount(line)
            }
        }
        return wrapped
    }

    override fun setStyles(styles: Styles?) {
        super.setStyles(wrap(styles))
    }

    /**
     * 差分更新の経路。**ここを素通しにすると重ねが黙って外れる** ──
     * 解析器が同じ [Styles] の `spans` を別の実体へ差し替えて渡してくることがあり、
     * そのとき `updateStyles` は `textStyles == styles` の速い道へ入って
     * [setStyles] を通らない（widget/CodeEditor.java:4267）。
     */
    override fun updateStyles(styles: Styles, range: StyleUpdateRange?) {
        super.updateStyles(wrap(styles), range)
    }

    fun setKeyBindingsEnabled(enabled: Boolean) {
        keyBindingsEnabled = enabled
    }

    fun areKeyBindingsEnabled(): Boolean = keyBindingsEnabled

    override fun canHandleKeyBinding(
        keyCode: Int,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
        altPressed: Boolean
    ): Boolean {
        if (!keyBindingsEnabled) return false
        return super.canHandleKeyBinding(keyCode, ctrlPressed, shiftPressed, altPressed)
    }

    /**
     * Backspace の行き先。**物理キーも Gboard もここへ集まる**
     * （`widget/EditorKeyEventHandler.java:184` の `KEYCODE_DEL` と、
     * `widget/EditorInputConnection.java:331` の `deleteSurroundingText(1, 0)`）。
     *
     * 空の括弧の間（`(|)`）では組ごと消す（[BracketPairs]）。Sora の `deleteText()` は
     * 組を見ずに1文字だけ消すので、`(` を打って Backspace すると `)` が残っていた。
     * **1回の削除なので Undo も1回で戻る。**
     */
    override fun deleteText() {
        if (deleteEmptyBracketPair()) return
        super.deleteText()
    }

    private fun deleteEmptyBracketPair(): Boolean {
        val cursor = cursor
        // 選択の削除と変換中の削除は Sora と IME の仕事。ここで割り込むと未確定の文字を壊す。
        if (cursor.isSelected || hasComposingText() || !props.symbolPairAutoCompletion) return false
        val line = cursor.leftLine
        val column = cursor.leftColumn
        if (!BracketPairs.deletesBoth(text.getLine(line), column, ::languageCloses)) return false
        text.delete(line, column - 1, line, column + 1)
        return true
    }

    /** 今の言語が [open] に対して自動で入れる閉じ。持っていなければ空。 */
    private fun languageCloses(open: Char): List<String> {
        val pairs = languageSymbolPairs ?: return emptyList()
        // TextMate の組は複数文字の表に入る（`TextMateSymbolPairMatch` が `putPair(String, …)` を使う）。
        // 1文字の表は他の言語のために見ておく。表には「この文字では閉じない」印の null も入りうる。
        return (pairs.matchBestPairList(open) + listOf(pairs.matchBestPairBySingleChar(open)))
            .filterNotNull()
            .filter { it.open == open.toString() }
            .map { it.close }
    }

    // ------------------------------------------------------------------
    // 閉じ括弧を打ったとき（2026-10-02）
    // ------------------------------------------------------------------

    /**
     * IME の `setComposingText` の最中。Sora は変換の1文字目をこの口（[commitText]）で入れてから
     * 未確定の範囲を張る（`widget/EditorInputConnection.java:440`）ので、まだ未確定に見えない。
     * **ここで踏み越したり字下げを動かしたりすると、未確定の範囲が別の文字に張られる。**
     */
    private var inComposingText = false

    fun <T> duringComposingText(block: () -> T): T {
        inComposingText = true
        try {
            return block()
        } finally {
            inComposingText = false
        }
    }

    /** Sora が自動で入れた閉じの位置。**踏み越してよいのはここに載っているものだけ。** */
    private val autoClosed = AutoClosed()

    private fun installAutoClosedTracking() {
        subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            val start = event.changeStart.index
            val length = event.changedText.length
            when {
                // 戻す・やり直すと、覚えた閉じが自動で入れたものかどうか分からなくなる。
                event.isCausedByUndoManager || event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT ->
                    autoClosed.clear()
                event.action == ContentChangeEvent.ACTION_INSERT -> autoClosed.onInsert(start, length)
                event.action == ContentChangeEvent.ACTION_DELETE -> autoClosed.onDelete(start, start + length)
            }
        }
        // カーソルが別の行へ移ったら忘れる ── 括弧の外へ出たあとで `)` を打って踏み越されると驚く。
        subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            if (autoClosed.isEmpty) return@subscribeEvent
            val content = text
            val line = cursor.leftLine
            autoClosed.keepOnly { it <= content.length && content.indexer.getCharPosition(it).line == line }
        }
    }

    /**
     * 文字を入れる口。**物理キーも Gboard も記号キー列もここへ集まる**
     * （`widget/EditorKeyEventHandler.java:440`、`widget/EditorInputConnection.java:297`、
     * [SoraEditorEngine.typeText]）。
     *
     * 1文字を打ったときだけ、Sora に無い2つを足す。選択中・変換中・貼り付け・改行は Sora のまま。
     *
     * - **閉じを踏み越す**: Sora が自動で入れた閉じの手前で同じ文字を打ったら、入れずにカーソルを進める（[AutoClosed]）
     * - **閉じ括弧の行を揃える**: 空白だけの行で `}` `]` `)` を打ったら、対応する開き括弧の行と同じ字下げにする
     *   （[BracketPairs.closingIndent]）。その言語が自動で閉じる括弧だけ ── `.txt` では何もしない
     */
    override fun commitText(committed: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        if (committed.length != 1 || cursor.isSelected || hasComposingText() || inComposingText) {
            super.commitText(committed, applyAutoIndent, applySymbolCompletion)
            return
        }
        val typed = committed[0]
        val pairs = applySymbolCompletion && props.symbolPairAutoCompletion
        if (pairs && overtypeAutoClosed(typed)) {
            trace?.event("boundary", "close.overtype", "char", typed.toString())
            return
        }
        val indent = if (applyAutoIndent && props.autoIndent) closingIndent(typed) else null
        if (indent == null) {
            val before = text.length
            super.commitText(committed, applyAutoIndent, applySymbolCompletion)
            if (pairs) noteAutoClosed(typed, before)
            return
        }
        // 字下げを揃えてから打つ。**挟んで1回の Undo で両方戻る**ようにする（`SoraEditorEngine.replaceRange` と同じ）。
        val content = text
        val line = cursor.leftLine
        val column = cursor.leftColumn
        content.beginBatchEdit()
        try {
            if (column > 0) {
                content.replace(line, 0, line, column, indent)
            } else {
                content.insert(line, 0, indent)
            }
            setSelection(line, indent.length)
            super.commitText(committed, applyAutoIndent, applySymbolCompletion)
        } finally {
            content.endBatchEdit()
        }
        trace?.event("boundary", "close.outdent", "char", typed.toString(), "from", column, "to", indent.length)
    }

    private fun overtypeAutoClosed(typed: Char): Boolean {
        if (autoClosed.isEmpty) return false
        val line = cursor.leftLine
        val column = cursor.leftColumn
        val chars = text.getLine(line)
        if (column >= chars.length || chars[column] != typed) return false
        if (!autoClosed.consume(cursor.left)) return false
        setSelection(line, column + 1)
        return true
    }

    /** 今打った [typed] に Sora が閉じを足したなら、その位置を覚える。 */
    private fun noteAutoClosed(typed: Char, lengthBefore: Int) {
        if (text.length != lengthBefore + 2) return
        val column = cursor.leftColumn
        val chars = text.getLine(cursor.leftLine)
        if (column <= 0 || column >= chars.length || chars[column - 1] != typed) return
        if (chars[column].toString() !in languageCloses(typed)) return
        autoClosed.add(cursor.left)
    }

    /** 閉じ括弧を打つ行をどの字下げにするか。今のままでよければ null。 */
    private fun closingIndent(typed: Char): String? {
        val open = BracketPairs.openerOf(typed) ?: return null
        if (typed.toString() !in languageCloses(open)) return null
        val line = cursor.leftLine
        val column = cursor.leftColumn
        val indent = BracketPairs.closingIndent({ text.getLine(it) }, line, column, typed) ?: return null
        return indent.takeIf { it != text.getLine(line).subSequence(0, column).toString() }
    }

    // ------------------------------------------------------------------
    // タブを切り替えても IME をつなぎ直さない（2026-10-02）
    // ------------------------------------------------------------------

    /**
     * 次の [restartInput] を1回だけ見送る印。タブを替えた後、行の幅を数え終えたときに来る分
     * （`widget/CodeEditor.java:3284` の `setLayoutBusy(false)`）。
     */
    private var skipNextRestart = false

    /** 本文が編集できない間に IME が接続を求めて断られた（[onCreateInputConnection] が null を返した）。 */
    private var connectionRefused = false

    /**
     * 出す文書を差し替える。**変換中でなければ IME をつなぎ直さない。**
     *
     * Sora の `setText` は IME をつなぎ直す（`widget/CodeEditor.java:4021` が `restartInput` を直に呼び、
     * 行の幅を数え終えた所でもう一度 `restartInput` する ＝ :3284）。つなぎ直すと IME は新しい入力欄として
     * 始め直すので、**タブを替えるたびに入力モードが初期化されることがある**（IME の挙動は環境による）。
     *
     * つなぎ直さなくても、IME は本文を問い合わせて読むので新しい文書が見える。
     * 位置だけは IME が覚えているので、差し替えた後に知らせる。
     *
     * 変換中はつなぎ直す ── 未確定の文字は前の文書の中の位置を指している。
     */
    fun switchContent(content: Content) {
        val field = IMM_FIELD
        val imm = field?.let { runCatching { it.get(this) }.getOrNull() }
        if (field == null || imm == null || hasComposingText()) {
            trace?.event("boundary", "switch.restart", "composing", hasComposingText())
            setText(content, true, null)
            return
        }
        // `setText` は `inputMethodManager` が null なら `restartInput` を飛ばす（:4020）。その間だけ外す。
        field.set(this, null)
        try {
            setText(content, true, null)
        } finally {
            field.set(this, imm)
        }
        skipNextRestart = true
        updateSelection()
        trace?.event("boundary", "switch.keep")
    }

    override fun restartInput() {
        val skip = skipNextRestart && !connectionRefused && !hasComposingText()
        skipNextRestart = false
        if (skip) {
            // 行の幅を数えている間に IME が打った分は Sora が断っている（`isEditable()` が false）。
            // 位置だけ知らせ直す。
            updateSelection()
            trace?.event("boundary", "restart.skip")
            return
        }
        super.restartInput()
    }

    // ------------------------------------------------------------------
    // ファイルの終わり（見た目案 01）
    // ------------------------------------------------------------------

    private val endOfFilePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 最後の行より下の空いた行に `~` を描く（vi / fresh と同じ印）。
     *
     * **ファイルがどこで終わっているかが見えない**のが fresh の調査で出た7点の1つだった ──
     * 空行が続くのか、ファイルがそこで終わっているのかが画面から区別できない。
     * Sora に同じ機能は無いので、Sora が描き終わった上に重ねる（本文には何も足さない）。
     * 色は行番号と同じ。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBracketPairGuide(canvas)
        val rows = layout?.rowCount ?: return
        val rowHeight = rowHeight
        if (rowHeight <= 0) return
        endOfFilePaint.textSize = textSizePx
        endOfFilePaint.typeface = typefaceText
        endOfFilePaint.color = colorScheme.getColor(EditorColorScheme.LINE_NUMBER)
        val x = measureTextRegionOffset()
        var row = rows
        while (getRowTop(row) - offsetY < height) {
            val baseline = getRowBaseline(row) - offsetY
            if (baseline > 0) canvas.drawText("~", x, baseline.toFloat(), endOfFilePaint)
            row++
        }
    }

    // ------------------------------------------------------------------
    // 今の括弧の組を結ぶ線（見た目案 B）
    // ------------------------------------------------------------------

    /** 組の線の色。配色を入れるたびにエンジンが渡す（Sora の配色に置き場が無い）。0 なら描かない。 */
    var bracketPairGuideColor = 0

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * カーソルが接している括弧の組だけを線で結ぶ。形は [BracketPairGuide]、ここは座標に直して描くだけ。
     *
     * 描かない時: 変換中・選択中・「対応する括弧を強調する」が off・折り返し中
     * （折り返すと行と段が1対1でなくなり、行の座標をそのまま使えない）。
     * 組の位置は Sora が括弧の強調に使っているもの（`styleDelegate`）をそのまま読む。
     */
    private fun drawBracketPairGuide(canvas: Canvas) {
        if (bracketPairGuideColor == 0 || !props.highlightMatchingDelimiters || !isHighlightBracketPair ||
            isWordwrap || cursor.isSelected || hasComposingText()
        ) return
        val pair = styleDelegate.foundBracketPair ?: return
        val content = text
        if (pair.leftIndex < 0 || pair.leftIndex >= pair.rightIndex ||
            pair.rightIndex + pair.rightLength > content.length
        ) return
        val open = content.indexer.getCharPosition(pair.leftIndex)
        val close = content.indexer.getCharPosition(pair.rightIndex)
        val width = tabWidth
        val openText = content.getLine(open.line)
        val closeText = content.getLine(close.line)
        val indent = BracketPairGuide.indentColumns(openText, width)
        val segments = BracketPairGuide.segments(
            open.line, BracketPairGuide.displayColumn(openText, open.column, width),
            close.line, BracketPairGuide.displayColumn(closeText, close.column, width),
            indent
        )
        if (segments.isEmpty()) return

        /** 表示の桁を画面の x に直す。**行ごとの実際の文字の位置から引く**ので、書体が等幅でなくても合う。 */
        fun xOf(line: Int, column: Int): Float =
            getCharOffsetX(line, BracketPairGuide.charIndexAt(content.getLine(line), column, width))

        guidePaint.color = bracketPairGuideColor
        guidePaint.strokeWidth = dpUnit * GUIDE_WIDTH_DP
        canvas.save()
        // 本文の領域の中だけ。左は行番号の列、上は固定見出しの帯の下 ── はみ出した線を描かない。
        canvas.clipRect(measureTextRegionOffset(), stuckBandBottom().toFloat(), this.width.toFloat(), this.height.toFloat())
        val guideX = xOf(open.line, indent)
        for (segment in segments) {
            when (segment) {
                is BracketPairGuide.Vertical -> {
                    val top = (getRowTop(segment.fromLine) - offsetY).toFloat()
                    val bottom = (getRowTop(segment.toLine) - offsetY).toFloat()
                    canvas.drawLine(guideX, top, guideX, bottom, guidePaint)
                }
                is BracketPairGuide.Horizontal -> {
                    val y = ((if (segment.atBottom) getRowBottom(segment.line) else getRowTop(segment.line)) - offsetY).toFloat()
                    canvas.drawLine(guideX, y, xOf(segment.line, segment.toColumn), y, guidePaint)
                }
            }
        }
        canvas.restore()
    }

    /**
     * 固定見出し（sticky scroll）の帯の下端。無ければ 0。
     *
     * Sora は帯の高さを公開していない（`EditorRenderer.lastStuckLines` は `protected`）ので、
     * 読めなければ 0 を返す ── 線が帯の上に重なるだけで、壊れはしない。
     * 帯は見出しの行ごとに1行ぶん（同じ開始行は1本）。
     */
    private fun stuckBandBottom(): Int {
        val field = STUCK_LINES_FIELD ?: return 0
        val blocks = runCatching { field.get(renderer) as? List<*> }.getOrNull() ?: return 0
        val rows = blocks.mapNotNull { (it as? CodeBlock)?.startLine }.distinct().size
        return if (rows == 0) 0 else getRowBottom(rows - 1)
    }

    /**
     * `CodeEditor.updateSelection()` は `protected`（widget/CodeEditor.java:4475）。
     * batch edit を握ると IME への位置通知が止まるので、境界が自分で呼ぶための口。
     */
    fun notifySelectionToIme() {
        updateSelection()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // super が inputConnection.reset() と text.resetBatchEdit() をするので
        // （widget/CodeEditor.java:4872）、境界が握っていた batch edit も一緒に消える。
        // wrap() の中で境界の状態も作り直す。
        val base = super.onCreateInputConnection(outAttrs)
        // 断った接続は、編集できるようになった所の [restartInput] で張り直してもらう。見送ると IME が死んだままになる。
        connectionRefused = base == null
        if (base == null) return null
        return boundary?.wrap(base) ?: base
    }

    /**
     * 本文がフォーカスを取った/失った瞬間を残す。
     *
     * **エンジンは一致へ飛ぶたび、遅れてフォーカスを取る**（`setSelectionRegion` の1行目が
     * `requestFocus()` ＝ `widget/CodeEditor.java:3630`、しかも要求は後回しにされる）。
     * それ自体は止められなかった ── 断っても取り返しても遅れの幅に負ける。だから
     * キーの行き先は `SearchBarView` が**所有権**で決めていて、ここはその前提が
     * 変わっていないかを見る観測点として残してある。
     */
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        trace?.event("boundary", "focus.change", "gained", gainFocus)
    }

    private companion object {
        const val GUIDE_WIDTH_DP = 1.5f
        const val GUTTER_MARGIN_DP = 8f

        /** Sora の `EditorRenderer.lastStuckLines`（protected、0.24.6）。読めなければ null。 */
        val STUCK_LINES_FIELD: Field? = runCatching {
            EditorRenderer::class.java.getDeclaredField("lastStuckLines")
                .apply { isAccessible = true }
        }.getOrNull()

        /**
         * Sora の `CodeEditor.inputMethodManager`（private、0.24.6）。**Sora を書き換えずに**
         * [switchContent] が `setText` の中の `restartInput` を飛ばすための口。
         * 名前が変わっていたら null になり、今までどおりつなぎ直す（壊れはしない）。
         */
        val IMM_FIELD: Field? = runCatching {
            CodeEditor::class.java.getDeclaredField("inputMethodManager").apply { isAccessible = true }
        }.getOrNull()
    }
}
