package dev.kirin.kiwa.engine.sora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.text.TextRunShaper
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import android.icu.util.ULocale
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.kirin.editoradapter.EditorEngine
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.util.LongArrayList
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.style.SelectionHandleStyle
import java.lang.reflect.Field
import java.util.IdentityHashMap

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
            if (isVertical && !hasComposingText() && keyBindingsEnabled && event.eventType == EditorKeyEvent.Type.DOWN) {
                val dir = when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> ArrowDirection.UP
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> ArrowDirection.DOWN
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> ArrowDirection.LEFT
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> ArrowDirection.RIGHT
                    else -> null
                }
                if (dir != null) {
                    val layout = getOrComputeVerticalLayout()
                    val (newLeft, newRight) = selectionController.computeSelection(
                        currentLeft = cursor.left,
                        currentRight = cursor.right,
                        isShiftPressed = event.isShiftPressed,
                        moveFocus = { focus -> layout.nextOffset(focus, dir) }
                    )
                    if (newLeft != newRight) {
                        val p1 = text.indexer.getCharPosition(newLeft)
                        val p2 = text.indexer.getCharPosition(newRight)
                        setSelectionRegion(p1.line, p1.column, p2.line, p2.column)
                        val focusOffset = if (selectionController.anchor == newLeft) newRight else newLeft
                        ensureVerticalPositionVisible(focusOffset)
                    } else {
                        val p = text.indexer.getCharPosition(newLeft)
                        setSelection(p.line, p.column)
                        ensureVerticalPositionVisible(newLeft)
                    }
                    event.markAsConsumed()
                    return@subscribeEvent
                } else if (!event.isShiftPressed) {
                    selectionController.reset()
                }
            }

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

        // 編集・Undo/Redo の1通知ごとに、変わった Sora の行だけ差し替える。
        subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            // 上限を超えたら、組み直す前にその場で横へ戻す。替えるのは描き方の印だけ。
            // Sora の設定にも IME のつなぎにも触れないので、変換の途中でも打っている字が落ちない
            // （アプリが設定を入れ直して戻していた頃は IME がつなぎ直され、実機で2字欠けた）。
            if (isVertical && text.length > EditorEngine.MAX_VERTICAL_CHARS) setVertical(false)
            updateVerticalLayout(event)
            if (verticalTouchSelection != null) cancelVerticalTouch()
            selectionController.reset()
            if (isVertical) resetVerticalBlink()
        }
        subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            if (isVertical) resetVerticalBlink()
        }

        // 行番号の左・行番号と区切り線の間・区切り線と本文の間に、それぞれ約 8dp（案 B）。
        // Sora の既定は 0 / 2dp / 2dp で、数字が画面の端と本文に貼り付いて見える。
        setLineNumberMarginLeft(dpUnit * GUTTER_MARGIN_DP)
        setDividerMargin(dpUnit * GUTTER_MARGIN_DP, dpUnit * GUTTER_MARGIN_DP)

        installTracing()
        installAutoClosedTracking()
        replaceComponent(EditorTextActionWindow::class.java, VerticalTextActionWindow(this))
        subscribeEvent(LayoutStateChangeEvent::class.java) { event, _ ->
            horizontalLayoutBusy = event.isLayoutBusy
            revealHorizontalCursor()
        }
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
        if (isVertical) {
            cancelVerticalTouch()
            actionWindow.dismiss()
        }
        verticalLayout = null
        verticalContent = null
        discardVerticalNodes()
        stopVerticalScroll()
        pendingVerticalReveal = true
        selectionController.reset()
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
    // タイプライタースクロール（ワープロ）
    // ------------------------------------------------------------------

    /** true の間、横は行、縦は列を画面の中ほどへ寄せる。 */
    var typewriter = false
        set(value) {
            if (field == value) return
            field = value
            if (isVertical) {
                stopVerticalScroll()
                val layout = getOrComputeVerticalLayout()
                verticalScrollX = verticalScrollX.coerceIn(0f, layout.scrollLimit(value))
                if (value) ensureVerticalPositionVisible(cursor.right)
                invalidate()
            }
        }

    /** 縦書き。ワープロタブでのみ有効化される。 */
    var isVertical: Boolean = false
        private set

    var verticalScrollX: Float = 0f
        private set

    private var verticalLayout: VerticalLayout? = null
    private var verticalContent: Content? = null
    private var pendingVerticalReveal = false
    private val verticalLineBreaks = BreakIterator.getLineInstance(ULocale("ja@lb=strict"))
    private val verticalScroller by lazy { OverScroller(context) }
    private var verticalVelocityTracker: VelocityTracker? = null
    private var verticalBlinkPeriod = CodeEditor.DEFAULT_CURSOR_BLINK_PERIOD
    private var verticalCursorVisible = true
    private var verticalBlinkTask: Runnable? = null
    private val selectionController = VerticalSelectionController()
    private val verticalTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verticalGlyphCache = HashMap<String, Boolean>()
    private val verticalGlyphBounds = Rect()
    private val verticalDashPath = Path()
    private val verticalDashBounds = RectF()
    private var verticalNodes = IdentityHashMap<Any, RenderNode>()
    private var verticalNodesColor = 0
    private val verticalCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verticalSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verticalSearchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verticalSearchBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val verticalMatchedPositions = LongArrayList()
    /** 壊れた正規表現で Sora が前の条件を残す時だけ、縦の古い色を抑える。 */
    internal var verticalSearchValid = true
        set(value) {
            field = value
            if (isVertical) invalidate()
        }
    private val verticalHandleDescriptor = SelectionHandleStyle.HandleDescriptor()
    private val verticalStartHandleBounds = RectF()
    private val verticalEndHandleBounds = RectF()
    private var verticalTouchSelection: VerticalTouchSelection? = null
    private var handlePointerX = 0f
    private var handlePointerY = 0f
    private var handleGrabX = 0f
    private var handleGrabY = 0f
    private val verticalComposingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verticalComposingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var verticalLongPressed = false
    private val verticalLongPressTask = Runnable {
        verticalLongPressed = true
        // 未確定範囲を選択で壊さない。UP もタップとして扱わない。
        if (!isVertical || hasComposingText() || isFormatting) return@Runnable
        requestFocus()
        val offset = getOrComputeVerticalLayout().offsetAt(touchDownX, touchDownY, verticalScrollX)
        val position = text.indexer.getCharPosition(offset)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectWord(position.line, position.column)
    }
    private val verticalEdgeScrollTask = object : Runnable {
        override fun run() {
            if (!isVertical || verticalTouchSelection == null) return
            val layout = getOrComputeVerticalLayout()
            val edge = dpUnit * 32f
            val step = when {
                handlePointerX < edge -> layout.columnWidth / 5f
                handlePointerX > width - edge -> -layout.columnWidth / 5f
                else -> 0f
            }
            if (step == 0f) return
            val next = (verticalScrollX + step).coerceIn(0f, layout.scrollLimit(typewriter))
            if (next == verticalScrollX) return
            verticalScrollX = next
            dragVerticalHandle()
            postOnAnimation(this)
        }
    }
    internal val verticalActionsBlocked: Boolean
        get() = verticalTouchSelection != null || isTouchDragging || !verticalScroller.isFinished
    private val actionWindow get() = getComponent(EditorTextActionWindow::class.java)

    private var touchStartScrollX = 0f
    private var isTouchDragging = false
    private val verticalViewConfig by lazy { ViewConfiguration.get(context) }
    private val touchSlop get() = verticalViewConfig.scaledTouchSlop

    /** 縦と横を替える。上限を超える本文は縦にしない。本文や IME の接続は替えない。 */
    fun setVertical(enabled: Boolean) {
        val vertical = enabled && text.length <= EditorEngine.MAX_VERTICAL_CHARS
        if (isVertical == vertical) return
        stopVerticalScroll()
        cancelVerticalTouch()
        eventHandler.reset2() // 横で残った拡大鏡・つまみの保持状態も解く。
        hideEditorWindows()
        isVertical = vertical
        verticalLayout = null
        verticalContent = null
        discardVerticalNodes()
        pendingVerticalReveal = vertical
        selectionController.reset()
        resetVerticalBlink()
        invalidate()
        // 横へ戻した時も、カーソルの行を画面内へ。横のスクロール位置は縦の間に動かないので、
        // 何もしないと縦で読み進めた分だけ離れた所が出る。編集の通知の中から来ることがあるので1拍おく。
        pendingHorizontalReveal = !vertical
        if (!vertical) post { revealHorizontalCursor() }
    }

    /**
     * 横へ戻した後の1回だけ、カーソルの行を画面内へ寄せる。**横の行の幅を数え終えてから。**
     * 切替の時にアプリが設定を入れ直すと Sora は行の幅を数え直す（`setTextSizePx` → `createLayout`）。
     * 数えている途中で寄せると、数え終えた所までしか送れず、本文の途中で止まる（実機で見た）。
     */
    private var pendingHorizontalReveal = false
    private var horizontalLayoutBusy = false

    private fun revealHorizontalCursor() {
        if (!pendingHorizontalReveal || isVertical || horizontalLayoutBusy) return
        pendingHorizontalReveal = false
        ensureSelectionVisible()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        verticalLayout = null
        if (isVertical) {
            stopVerticalScroll()
            pendingVerticalReveal = true
        }
    }

    private fun verticalParagraphs(first: Int, last: Int): List<VerticalParagraph> =
        (first..last).map { i ->
            val line = text.getLine(i)
            VerticalParagraph(line.toString(), line.lineSeparator.length)
        }

    private fun updateVerticalLayout(event: ContentChangeEvent) {
        val cached = verticalLayout ?: return
        val first = event.changeStart.line
        val end = event.changeEnd.line
        val insert = event.action == ContentChangeEvent.ACTION_INSERT
        val delete = event.action == ContentChangeEvent.ACTION_DELETE
        val removed = if (insert) 1 else end - first + 1
        val added = if (insert) end - first + 1 else 1
        val expectedLength = cached.textLength + if (insert) event.changedText.length else -event.changedText.length
        if (!isVertical || (!insert && !delete) || verticalContent !== text || first < 0 || end < first ||
            removed <= 0 || first + removed > cached.paragraphCount || first + added > text.lineCount ||
            cached.paragraphCount - removed + added != text.lineCount || expectedLength != text.length
        ) {
            verticalLayout = null
            return
        }
        // Content.java:439 は挿入後の終点、:552 と CodeEditor.java:5307-5310 は削除前の終点。
        // 挿入: 旧1行→新 start..end。削除: 旧 start..end→新 start の1行。
        cached.replaceParagraphs(first, removed, verticalParagraphs(first, first + added - 1))
        verticalScrollX = verticalScrollX.coerceIn(0f, cached.scrollLimit(typewriter))
        invalidate()
    }

    private fun getOrComputeVerticalLayout(): VerticalLayout {
        val cached = verticalLayout
        val content = text
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val lineHeight = textSizePx
        if (cached != null &&
            verticalContent === content &&
            cached.viewportWidth == w &&
            cached.viewportHeight == h &&
            cached.lineHeight == lineHeight &&
            cached.columnWidth == rowHeight.toFloat().coerceAtLeast(textSizePx * 1.2f) &&
            verticalTextPaint.typeface == typefaceText
        ) {
            return cached
        }
        return computeVerticalLayout()
    }

    private fun computeVerticalLayout(): VerticalLayout {
        val content = text
        val colWidth = rowHeight.toFloat().coerceAtLeast(textSizePx * 1.2f)
        val lineHeight = textSizePx
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val padTop = paddingTop.toFloat() + dpUnit * 8f
        val padBottom = paddingBottom.toFloat() + dpUnit * 8f
        val padLeft = paddingLeft.toFloat() + dpUnit * 8f
        val padRight = paddingRight.toFloat() + dpUnit * 8f

        if (verticalTextPaint.typeface != typefaceText) verticalGlyphCache.clear()
        verticalTextPaint.textSize = textSizePx
        verticalTextPaint.typeface = typefaceText
        verticalTextPaint.fontFeatureSettings = null

        val layout = VerticalLayout.build(
            paragraphs = verticalParagraphs(0, content.lineCount - 1),
            lineHeight = lineHeight,
            columnWidth = colWidth,
            viewportWidth = w,
            viewportHeight = h,
            paddingTop = padTop,
            paddingBottom = padBottom,
            paddingLeft = padLeft,
            paddingRight = padRight,
            orientation = { cp ->
                when (UCharacter.getIntPropertyValue(cp, UProperty.VERTICAL_ORIENTATION)) {
                    UCharacter.VerticalOrientation.UPRIGHT -> VerticalOrientation.U
                    UCharacter.VerticalOrientation.TRANSFORMED_UPRIGHT -> VerticalOrientation.TU
                    UCharacter.VerticalOrientation.TRANSFORMED_ROTATED -> VerticalOrientation.TR
                    else -> VerticalOrientation.R
                }
            },
            hasVerticalGlyph = { cluster ->
                verticalGlyphCache.getOrPut(cluster) {
                    val features = verticalTextPaint.fontFeatureSettings
                    try {
                        verticalTextPaint.fontFeatureSettings = null
                        val plain = TextRunShaper.shapeTextRun(
                            cluster, 0, cluster.length, 0, cluster.length, 0f, 0f, false, verticalTextPaint
                        )
                        verticalTextPaint.fontFeatureSettings = "'vert' 1"
                        val vertical = TextRunShaper.shapeTextRun(
                            cluster, 0, cluster.length, 0, cluster.length, 0f, 0f, false, verticalTextPaint
                        )
                        plain.glyphCount() != vertical.glyphCount() ||
                            (0 until plain.glyphCount()).any { plain.getGlyphId(it) != vertical.getGlyphId(it) }
                    } finally {
                        verticalTextPaint.fontFeatureSettings = features
                    }
                }
            },
            lineBreaks = { paragraph ->
                val iterator = verticalLineBreaks
                iterator.setText(paragraph)
                buildSet {
                    var boundary = iterator.first()
                    while (boundary != BreakIterator.DONE) {
                        add(boundary)
                        boundary = iterator.next()
                    }
                }
            },
            sidewaysAdvance = { cluster ->
                verticalTextPaint.fontFeatureSettings = null
                if (isVerticalDash(cluster)) lineHeight else verticalTextPaint.measureText(cluster)
            }
        )
        verticalLayout = layout
        verticalContent = content
        // **スクロール量はここで範囲へ収める。** 本文が縮むと列が減って上限が下がる。描く時だけ丸めると、
        // タップの位置合わせは丸める前の値を使い、描いた列と別の列に当たる。
        verticalScrollX = verticalScrollX.coerceIn(0f, layout.scrollLimit(typewriter))
        return layout
    }

    /** 並べてつなげるダッシュ（U+2015・U+2014）。送りは1マスにし、マスの端から端まで線として描く。 */
    private fun isVerticalDash(cluster: String): Boolean = cluster == "\u2015" || cluster == "\u2014"

    private fun drawVertical(canvas: Canvas) {
        canvas.drawColor(colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
        if (width <= 0 || height <= 0) return

        val layout = getOrComputeVerticalLayout()
        if (pendingVerticalReveal) {
            ensureVerticalPositionVisible(cursor.right)
            pendingVerticalReveal = false
        }
        val scrollX = verticalScrollX

        val textColor = colorScheme.getColor(EditorColorScheme.TEXT_NORMAL)
        if (textColor != verticalNodesColor) {
            discardVerticalNodes()
            verticalNodesColor = textColor
        }
        verticalTextPaint.color = textColor
        verticalSelectionPaint.color = colorScheme.getColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND)
        verticalCursorPaint.color = colorScheme.getColor(EditorColorScheme.SELECTION_INSERT)
        verticalCursorPaint.strokeWidth = dpUnit * 2f

        val cursor = cursor
        val selLeft = cursor.left
        val selRight = cursor.right
        val hasSelection = cursor.isSelected

        val composing = boundary?.validComposingRange()
        val compStart = composing?.startIndex ?: -1
        val compEnd = composing?.endIndex ?: -1
        val hasComp = compStart in 0 until compEnd

        // 選択と変換中の背景は毎回描く。マスを読むのは、その範囲にかかる列だけ。
        val selColumns = if (hasSelection) layout.columnIndexOf(selLeft)..layout.columnIndexOf(selRight) else IntRange.EMPTY
        val compColumns = if (hasComp) layout.columnIndexOf(compStart)..layout.columnIndexOf(compEnd) else IntRange.EMPTY
        val useNodes = canvas.isHardwareAccelerated
        val kept = IdentityHashMap<Any, RenderNode>()
        val searchByLine = HashMap<Int, List<Pair<Int, Int>>>()
        val showSearch = verticalSearchValid && searcher.hasQuery()
        verticalSearchPaint.color = colorScheme.getColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND)
        verticalSearchBorderPaint.color = colorScheme.getColor(EditorColorScheme.MATCHED_TEXT_BORDER)
        verticalSearchBorderPaint.strokeWidth = dpUnit

        for (index in layout.columnIndexRangeInX(-scrollX, width - scrollX)) {
            val colRight = layout.columnRightX(index) + scrollX
            val colLeft = colRight - layout.columnWidth
            var column: VerticalColumn? = null

            if (showSearch) {
                column = layout.columns[index]
                val line = text.indexer.getCharPosition(column.startOffset).line
                val ranges = searchByLine.getOrPut(line) {
                    // 横の描画と同じ既存結果。複数行の正規表現も Sora 自身が判定する。
                    computeMatchedPositions(line, verticalMatchedPositions)
                    val lineStart = text.getCharIndex(line, 0)
                    (0 until verticalMatchedPositions.size()).map { i ->
                        val range = verticalMatchedPositions.get(i)
                        lineStart + IntPair.getFirst(range) to lineStart + IntPair.getSecond(range)
                    }
                }
                // 一致は Sora が位置順に持つ。長い1段落でも、この列にかかる一致だけを読む。
                val found = ranges.binarySearchBy(column.startOffset) { it.second }
                val from = if (found >= 0) found + 1 else -found - 1
                for (i in from until ranges.size) {
                    val (start, end) = ranges[i]
                    if (start >= column.endOffset) break
                    val first = column.glyphs.firstOrNull { it.overlaps(start, end) } ?: continue
                    val last = column.glyphs.last { it.overlaps(start, end) }
                    canvas.drawRect(colLeft, first.topY, colRight, last.bottomY, verticalSearchPaint)
                    canvas.drawRect(colLeft, first.topY, colRight, last.bottomY, verticalSearchBorderPaint)
                }
            }
            if (index in selColumns || index in compColumns) {
                column = column ?: layout.columns[index]
                for (glyph in column.glyphs) {
                    if (hasSelection && glyph.overlaps(selLeft, selRight)) {
                        canvas.drawRect(colLeft, glyph.topY, colRight, glyph.bottomY, verticalSelectionPaint)
                    }
                    if (hasComp && glyph.overlaps(compStart, compEnd)) {
                        verticalComposingPaint.color = colorScheme.getColor(EditorColorScheme.TEXT_HIGHLIGHT_BACKGROUND)
                        canvas.drawRect(colLeft, glyph.topY, colRight, glyph.bottomY, verticalComposingPaint)
                        verticalComposingLinePaint.color = colorScheme.getColor(EditorColorScheme.SELECTION_INSERT)
                        verticalComposingLinePaint.strokeWidth = dpUnit * 1.5f
                        canvas.drawLine(colRight - dpUnit, glyph.topY, colRight - dpUnit, glyph.bottomY, verticalComposingLinePaint)
                    }
                }
            }

            canvas.save()
            if (useNodes) {
                // 列の字は1度描いた結果を覚え、変わっていない列は貼るだけにする。画面いっぱい（約2,000字）を
                // 1字ずつ描き直すと、点滅だけの枠でも 65ms かかった（実機、2026-11-12）。
                val key = layout.columnKey(index)
                val node = verticalNodes[key] ?: recordVerticalColumn(column ?: layout.columns[index], layout)
                kept[key] = node
                // 覚えた列は画素の境目に置く（半端な位置に貼ると字がにじむ）。
                canvas.translate(Math.round(colLeft).toFloat(), 0f)
                canvas.drawRenderNode(node)
            } else {
                canvas.translate(colLeft, 0f)
                drawVerticalColumnText(canvas, column ?: layout.columns[index], layout)
            }
            canvas.restore()
        }
        // 覚えておくのは今の画面の列だけ（数十列）。外れた列は捨て、戻ってきたら描き直す。
        for ((key, node) in verticalNodes) if (!kept.containsKey(key)) node.discardDisplayList()
        verticalNodes = kept

        if (hasSelection) {
            drawVerticalHandle(canvas, layout.selectionHandle(selLeft, true, scrollX), true, verticalStartHandleBounds)
            drawVerticalHandle(canvas, layout.selectionHandle(selRight, false, scrollX), false, verticalEndHandleBounds)
        } else {
            verticalStartHandleBounds.setEmpty()
            verticalEndHandleBounds.setEmpty()
            if (verticalCursorVisible) {
                val curPos = layout.cursorPosition(selLeft, scrollX)
                canvas.drawLine(curPos.x1, curPos.y, curPos.x2, curPos.y, verticalCursorPaint)
            }
        }
    }

    private fun drawVerticalHandle(canvas: Canvas, point: VerticalHandlePoint, start: Boolean, bounds: RectF) {
        canvas.save()
        canvas.translate(point.x, point.y)
        canvas.rotate(90f)
        handleStyle.draw(
            canvas, if (start) SelectionHandleStyle.HANDLE_TYPE_LEFT else SelectionHandleStyle.HANDLE_TYPE_RIGHT,
            0f, 0f, rowHeight, colorScheme.getColor(EditorColorScheme.SELECTION_HANDLE), verticalHandleDescriptor
        )
        canvas.restore()
        val r = verticalHandleDescriptor.position
        bounds.set(point.x - r.bottom, point.y + r.left, point.x - r.top, point.y + r.right)
    }

    internal fun verticalTextActionPosition(width: Int, height: Int): VerticalHandlePoint =
        getOrComputeVerticalLayout().textActionPosition(
            cursor.left, cursor.right, verticalScrollX, width.toFloat(), height.toFloat(), dpUnit * 24f
        )

    private fun recordVerticalColumn(column: VerticalColumn, layout: VerticalLayout): RenderNode {
        val node = RenderNode("kiwa-vertical-column")
        node.setPosition(0, 0, kotlin.math.ceil(layout.columnWidth).toInt(), height)
        val recording = node.beginRecording()
        try {
            drawVerticalColumnText(recording, column, layout)
        } finally {
            node.endRecording()
        }
        // 列ごとに1枚の画として持たせる。貼るだけの枠で、字を描き直さない。
        node.setUseCompositingLayer(true, null)
        return node
    }

    private fun discardVerticalNodes() {
        for (node in verticalNodes.values) node.discardDisplayList()
        verticalNodes.clear()
    }

    /** 1列ぶんの字を、列の左端を x=0 とした座標で描く。色は [verticalTextPaint] に入っている物を使う。 */
    private fun drawVerticalColumnText(canvas: Canvas, column: VerticalColumn, layout: VerticalLayout) {
        val fontMetrics = verticalTextPaint.fontMetrics
        val cx = layout.columnWidth / 2f
        for (glyph in column.glyphs) {
            // 正立は縦用字形、Sidewaysは横用字形を回す。縦字形をさらに回す二重回転を避ける。
            verticalTextPaint.fontFeatureSettings = if (glyph.isUpright && !glyph.isTateChuYoko) "'vert' 1" else null
            val cy = glyph.centerY
            if (glyph.isWhitespace) continue

            if (isVerticalDash(glyph.text)) {
                // 字形を回して描くと、字形の両端の余白と、端末の「文字の縁取り」（描くたびに黒い縁が付く）で、
                // 並べた線の間が切れる。フォントの線の太さだけを借り、マスの端から端まで線として描く。
                // 太さは字形の輪郭から小数のまま読む（整数の外枠だと1pxほど太る）。
                verticalTextPaint.getTextPath(glyph.text, 0, glyph.text.length, 0f, 0f, verticalDashPath)
                verticalDashPath.computeBounds(verticalDashBounds, true)
                val half = verticalDashBounds.height().coerceAtLeast(1f) / 2f
                canvas.drawRect(cx - half, glyph.topY, cx + half, glyph.bottomY, verticalTextPaint)
                continue
            }

            if (glyph.isTateChuYoko) {
                val scale = verticalTextPaint.textScaleX
                try {
                    val width = verticalTextPaint.measureText(glyph.text)
                    if (width > layout.lineHeight) {
                        verticalTextPaint.textScaleX = scale * layout.lineHeight / width
                    }
                    val charW = verticalTextPaint.measureText(glyph.text)
                    val baselineY = cy - (fontMetrics.descent + fontMetrics.ascent) / 2f
                    canvas.drawText(glyph.text, cx - charW / 2f, baselineY, verticalTextPaint)
                } finally {
                    verticalTextPaint.textScaleX = scale
                }
            } else if (glyph.isUpright) {
                val charW = verticalTextPaint.measureText(glyph.text)
                val drawX = cx - charW / 2f
                val baselineY = cy - (fontMetrics.descent + fontMetrics.ascent) / 2f
                canvas.drawText(glyph.text, drawX, baselineY, verticalTextPaint)
            } else {
                val charW = verticalTextPaint.measureText(glyph.text)
                canvas.save()
                canvas.rotate(90f, cx, cy)
                val cp = glyph.text.codePointAt(0)
                val isLetterNumberMark = when (Character.getType(cp)) {
                    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
                    Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
                    Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
                    Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
                    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt() -> true
                    else -> false
                }
                val drawX: Float
                val baselineY: Float
                if (cp > 0x7E && !isLetterNumberMark) {
                    verticalTextPaint.getTextBounds(glyph.text, 0, glyph.text.length, verticalGlyphBounds)
                    drawX = cx - verticalGlyphBounds.exactCenterX()
                    baselineY = cy - verticalGlyphBounds.exactCenterY()
                } else {
                    drawX = cx - charW / 2f
                    baselineY = cy - (fontMetrics.descent + fontMetrics.ascent) / 2f
                }
                canvas.drawText(glyph.text, drawX, baselineY, verticalTextPaint)
                canvas.restore()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isVertical) return super.onTouchEvent(event)
        if (!isEnabled) return false
        verticalVelocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopVerticalScroll()
                cancelVerticalTouch()
                verticalVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                touchDownX = event.x
                touchDownY = event.y
                touchStartScrollX = verticalScrollX
                selectionController.reset()
                parent?.requestDisallowInterceptTouchEvent(true)
                val startHandle = if (event.isFromSource(InputDevice.SOURCE_MOUSE)) null else hitVerticalHandle(event.x, event.y)
                if (startHandle != null && !hasComposingText()) {
                    verticalTouchSelection = VerticalTouchSelection(startHandle, cursor.left, cursor.right)
                    val point = getOrComputeVerticalLayout().selectionHandle(
                        if (startHandle) cursor.left else cursor.right, startHandle, verticalScrollX
                    )
                    handleGrabX = point.x - event.x
                    handleGrabY = point.y - event.y
                    handlePointerX = event.x
                    handlePointerY = event.y
                    actionWindow.dismiss()
                } else if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    postDelayed(verticalLongPressTask, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (verticalTouchSelection != null) {
                    handlePointerX = event.x
                    handlePointerY = event.y
                    dragVerticalHandle()
                    removeCallbacks(verticalEdgeScrollTask)
                    postOnAnimation(verticalEdgeScrollTask)
                } else if (!verticalLongPressed) {
                    val dx = event.x - touchDownX
                    if (!isTouchDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(event.y - touchDownY) > touchSlop)) {
                        removeCallbacks(verticalLongPressTask)
                        isTouchDragging = true
                        actionWindow.dismiss()
                    }
                    if (isTouchDragging) {
                        verticalScrollX = (touchStartScrollX + dx).coerceIn(0f, getOrComputeVerticalLayout().scrollLimit(typewriter))
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(verticalLongPressTask)
                val selectedByTouch = verticalTouchSelection != null || verticalLongPressed
                if (verticalTouchSelection != null) {
                    handlePointerX = event.x
                    handlePointerY = event.y
                    dragVerticalHandle()
                } else if (!isTouchDragging && !verticalLongPressed) {
                    val offset = getOrComputeVerticalLayout().offsetAt(event.x, event.y, verticalScrollX)
                    val pos = text.indexer.getCharPosition(offset)
                    setSelection(pos.line, pos.column)
                    if (isEditable) showSoftInput() else requestFocus()
                } else if (isTouchDragging) {
                    verticalVelocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000, verticalViewConfig.scaledMaximumFlingVelocity.toFloat())
                        val velocity = tracker.xVelocity
                        if (kotlin.math.abs(velocity) >= verticalViewConfig.scaledMinimumFlingVelocity) {
                            verticalScroller.fling(
                                verticalScrollX.toInt(), 0, velocity.toInt(), 0,
                                0, getOrComputeVerticalLayout().scrollLimit(typewriter).toInt(), 0, 0
                            )
                            postInvalidateOnAnimation()
                        }
                    }
                }
                val restoreActions = selectedByTouch || isTouchDragging
                cancelVerticalTouch()
                if (restoreActions && cursor.isSelected) {
                    if (verticalScroller.isFinished) actionWindow.displayWindow() else verticalShowActionsAfterScroll = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(verticalLongPressTask)
                // 送っている最中に別の指が触れても、送りは止めない。まだ動いていなければ、
                // この操作はタップにも長押しにもしない。
                if (!isTouchDragging) verticalLongPressed = true
            }
            MotionEvent.ACTION_POINTER_UP -> if (isTouchDragging) {
                // 離れなかった指へ基準を移す。移さないと、event.x の指が替わった分だけ列が飛ぶ。
                touchDownX = event.getX(if (event.actionIndex == 0) 1 else 0)
                touchStartScrollX = verticalScrollX
            }
            MotionEvent.ACTION_CANCEL -> cancelVerticalTouch()
        }
        // 縦の間は Sora のタッチ処理へ何も渡さない。拡大鏡・横のつまみも起動しない。
        return true
    }

    private fun hitVerticalHandle(x: Float, y: Float): Boolean? {
        if (!cursor.isSelected) return null
        val half = dpUnit * 24f
        fun hits(r: RectF): Boolean = !r.isEmpty &&
            x >= minOf(r.left, r.centerX() - half) && x <= maxOf(r.right, r.centerX() + half) &&
            y >= minOf(r.top, r.centerY() - half) && y <= maxOf(r.bottom, r.centerY() + half)
        val first = hits(verticalStartHandleBounds)
        val last = hits(verticalEndHandleBounds)
        if (!first && !last) return null
        // 当たりが重なる短い選択では、指から近いほうを取る。
        fun distance(r: RectF) = (x - r.centerX()) * (x - r.centerX()) + (y - r.centerY()) * (y - r.centerY())
        return first && (!last || distance(verticalStartHandleBounds) <= distance(verticalEndHandleBounds))
    }

    private fun dragVerticalHandle() {
        val drag = verticalTouchSelection ?: return
        val (left, right) = drag.drag(getOrComputeVerticalLayout(), handlePointerX + handleGrabX, handlePointerY + handleGrabY, verticalScrollX)
        if (left != cursor.left || right != cursor.right) {
            val first = text.indexer.getCharPosition(left)
            val last = text.indexer.getCharPosition(right)
            setSelectionRegion(first.line, first.column, last.line, last.column, false, SelectionChangeEvent.CAUSE_SELECTION_HANDLE)
        }
        invalidate()
    }

    private fun cancelVerticalTouch() {
        removeCallbacks(verticalLongPressTask)
        removeCallbacks(verticalEdgeScrollTask)
        verticalTouchSelection = null
        verticalVelocityTracker?.recycle()
        verticalVelocityTracker = null
        isTouchDragging = false
        verticalLongPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private var verticalShowActionsAfterScroll = false

    private fun stopVerticalScroll() {
        verticalScroller.forceFinished(true)
        verticalShowActionsAfterScroll = false
    }

    override fun computeScroll() {
        if (!isVertical) {
            super.computeScroll()
            return
        }
        if (verticalScroller.computeScrollOffset()) {
            verticalScrollX = verticalScroller.currX.toFloat().coerceIn(0f, getOrComputeVerticalLayout().scrollLimit(typewriter))
            postInvalidateOnAnimation()
        } else if (verticalShowActionsAfterScroll) {
            verticalShowActionsAfterScroll = false
            if (cursor.isSelected) actionWindow.displayWindow()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isVertical) return super.onGenericMotionEvent(event)
        if (isEnabled && event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            stopVerticalScroll()
            val layout = getOrComputeVerticalLayout()
            // 正の scrollX は左の列へ進む。HSCROLL 正は右へ送るので符号を反転する。
            val steps = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) - event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            actionWindow.dismiss()
            verticalScrollX = (verticalScrollX + steps * layout.columnWidth * 3f).coerceIn(0f, layout.scrollLimit(typewriter))
            invalidate()
        }
        // 縦では Sora の横書き用 generic motion を一切動かさない。
        return true
    }

    override fun setCursorBlinkPeriod(period: Int) {
        super.setCursorBlinkPeriod(period)
        verticalBlinkPeriod = period
        if (isVertical) resetVerticalBlink()
    }

    private fun resetVerticalBlink() {
        verticalBlinkTask?.let { removeCallbacks(it) }
        verticalCursorVisible = true
        if (!isVertical) return
        invalidate()
        if (!isAttachedToWindow || !hasFocus() || cursor.isSelected || verticalBlinkPeriod <= 0) return
        val task = verticalBlinkTask ?: object : Runnable {
            override fun run() {
                if (!isVertical || !isAttachedToWindow || !hasFocus() || cursor.isSelected || verticalBlinkPeriod <= 0) return
                verticalCursorVisible = !verticalCursorVisible
                invalidate()
                postDelayed(this, verticalBlinkPeriod.toLong())
            }
        }.also { verticalBlinkTask = it }
        // 横の CursorBlink と同じく、移動直後は2周期の間見せ、以後は1周期ごとに反転。
        postDelayed(task, verticalBlinkPeriod * 2L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isVertical) resetVerticalBlink()
    }

    override fun onDetachedFromWindow() {
        verticalBlinkTask?.let { removeCallbacks(it) }
        stopVerticalScroll()
        cancelVerticalTouch()
        discardVerticalNodes()
        super.onDetachedFromWindow()
    }

    private fun ensureVerticalPositionVisible(offset: Int) {
        stopVerticalScroll()
        if (width <= 0 || height <= 0) {
            pendingVerticalReveal = true
            return
        }
        val layout = getOrComputeVerticalLayout()
        val target = layout.scrollToOffset(offset, verticalScrollX, typewriter)
        if (target != verticalScrollX) {
            actionWindow.dismiss()
            verticalScrollX = target
            invalidate()
        }
    }

    override fun updateCursorAnchor(): Float {
        if (!isVertical) return super.updateCursorAnchor()
        val layout = getOrComputeVerticalLayout()
        val curPos = layout.cursorPosition(cursor.right, verticalScrollX)

        // Sora の元の実装と同じく、変換中の文字を差し替えている途中の位置は IME へ知らせない。
        if (boundary?.soraComposingText()?.preSetComposing == true) return curPos.centerX

        val visible = curPos.centerX in 0f..width.toFloat() && curPos.y in 0f..height.toFloat()

        val imm = runCatching {
            IMM_FIELD?.get(this) as? InputMethodManager
        }.getOrNull()

        if (imm != null && props.reportCursorAnchor) {
            val builder = CursorAnchorInfo.Builder()
            builder.reset()
            val m = Matrix(matrix)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            m.postTranslate(loc[0].toFloat(), loc[1].toFloat())
            builder.setMatrix(m)
            builder.setSelectionRange(cursor.left, cursor.right)

            val composing = boundary?.validComposingRange()
            if (composing != null && composing.isComposing) {
                val content = text
                if (composing.startIndex in 0..content.length && composing.endIndex in composing.startIndex..content.length) {
                    builder.setComposingText(composing.startIndex, content.subSequence(composing.startIndex, composing.endIndex))
                }
            }

            val flags = if (visible) {
                CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION
            } else {
                CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION
            }
            builder.setInsertionMarkerLocation(curPos.centerX, curPos.lineTop, curPos.y, curPos.lineBottom, flags)
            imm.updateCursorAnchorInfo(this, builder.build())
        }

        return curPos.centerX
    }

    /**
     * Sora が「カーソルを見える所へ」寄せる口（`widget/CodeEditor.java:2249`。公開で final でない）。
     * 本文を打つ・消す（:5104 `afterInsert`、:5344 `afterDelete`）・選択を動かす・検索で飛ぶ、が全部ここを通る。
     *
     * Sora の寄せ方は「見える範囲の外に出た時だけ最小限動く」なので、そのままだと打っている行が
     * 画面の下端に張り付く。**横は Sora に任せ**（折り返しを切った長い行で要る）、
     * 縦だけ行の中央が画面の中央に来る位置へ上書きする。
     * 端は `0` と `scrollMaxY` で止まるので、先頭近くでは動かない。
     */
    override fun ensurePositionVisible(line: Int, column: Int, noAnimation: Boolean) {
        if (isVertical) {
            val content = text
            if (line in 0 until content.lineCount) {
                val pos = content.getCharIndex(line, column)
                ensureVerticalPositionVisible(pos)
            }
            return
        }
        super.ensurePositionVisible(line, column, noAnimation || typewriter)
        if (!typewriter) return
        val layout = layout ?: return
        val rowBottom = layout.getCharLayoutOffset(line, column)[0]
        val target = (rowBottom - rowHeight / 2f - height / 2f)
            .coerceIn(0f, scrollMaxY.toFloat())
        if (kotlin.math.abs(target - offsetY) < 1f) return
        scroller.startScroll(offsetX, offsetY, 0, (target - offsetY).toInt(), 0)
        scroller.abortAnimation()
        invalidate()
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
        if (isVertical) {
            // Sora は横のスクロール位置を View の scrollX / scrollY にも入れている（EditorScroller.java:42-43）。
            // View はその分だけずらした canvas を渡すので、打ち消してから描く（Sora 自身の描画も同じ:
            // EditorRenderer.java:192）。打ち消さないと、横で下へ送った本文を縦にした時に何も見えない。
            canvas.save()
            canvas.translate(getScrollX().toFloat(), getScrollY().toFloat())
            drawVertical(canvas)
            canvas.restore()
            return
        }
        super.onDraw(canvas)
        drawBracketPairGuide(canvas)
        // 行番号を出さないとき（ワープロ）は `~` も出さない ── 行番号と同じ列・同じ色の印なので。
        if (!isLineNumberEnabled) return
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
        if (isVertical) {
            if (!gainFocus) cancelVerticalTouch()
            resetVerticalBlink()
        }
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
