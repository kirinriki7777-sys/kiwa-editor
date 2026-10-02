package dev.kirin.kiwa.engine.sora

import android.view.inputmethod.InputConnection
import dev.kirin.editoradapter.ComposingDecoration
import dev.kirin.editoradapter.EditorTheme
import dev.kirin.editoradapter.ImeBoundary
import dev.kirin.editoradapter.TextPosition
import dev.kirin.editoradapter.TextRange
import io.github.rosemoe.sora.lang.styling.span.SpanColorResolver
import io.github.rosemoe.sora.lang.styling.span.SpanExtAttrs
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * [ImeBoundary] の Sora 実装。
 *
 * 4つの責務の「実行」はここ、「いつ呼ぶか」は [ComposingBoundaryConnection] が決める。
 * 分けてあるのは、呼ぶ側が InputConnection の契約に縛られるのに対し、
 * こちら側はエンジンの API だけに縛られるため ── 載せ替えるとき差し替わるのはこちらだけ。
 */
internal class SoraImeBoundary(
    private val editor: KiwaCodeEditor,
    internal val trace: ImeTrace?
) : ImeBoundary {

    /** 境界が握っている `Content` の batch edit（0段か1段のどちらか）。 */
    private var scopeOpen = false

    /** 変換中の装飾をどれだけ濃く塗るかを決めるのに要る。 */
    private var theme: EditorTheme = EditorTheme.SUMI

    fun setTheme(theme: EditorTheme) {
        this.theme = theme
    }

    /**
     * composing の範囲。**自前で数え上げているのではなく、更新のたびにエンジンの
     * カーソルから引き直した値**を置いてある（[ComposingBoundaryConnection.noteComposingRange]）。
     * Sora の `ComposingText` は package-private で外から読めないので、読める形に写している。
     */
    private var composingStart = -1
    private var composingEnd = -1

    fun wrap(base: InputConnection): InputConnection {
        trace?.event("boundary", "connection.new")
        // 接続が作り直されると Sora 側が resetBatchEdit() するので、こちらの段数も 0 に戻す。
        scopeOpen = false
        composingStart = -1
        composingEnd = -1
        editor.setKeyBindingsEnabled(true)
        return ComposingBoundaryConnection(base, editor, this)
    }

    // ------------------------------------------------------------------
    // 責務1: IME が付けた装飾を受けて描く
    // ------------------------------------------------------------------

    override fun setComposingDecorations(decorations: List<ComposingDecoration>) {
        val content = editor.text
        val length = content.length
        val ranges = ArrayList<ComposingOverlay.Range>(decorations.size)
        for (d in decorations) {
            if (d.isEmpty) continue
            val startIndex = d.startIndex().coerceIn(0, length)
            val endIndex = d.endIndex().coerceIn(startIndex, length)
            if (startIndex == endIndex) continue
            val start = content.indexer.getCharPosition(startIndex)
            val end = content.indexer.getCharPosition(endIndex)
            val color = background(d.backgroundColor())
            // 装飾が行をまたぐことは日本語の変換ではまず無いが、割っておく。
            // スパンは行ごとに持つものなので、またいだままでは置き場が無い。
            for (line in start.line..end.line) {
                val from = if (line == start.line) start.column else 0
                val to = if (line == end.line) end.column else content.getColumnCount(line)
                if (from < to) ranges.add(ComposingOverlay.Range(line, from, to, color))
            }
        }
        // Content にスパンは書き込めない（text/ContentLine.java:40 が char ベース）。
        // 重ねはバッファの外に置き、読み出しのときだけ構文スパンへ重ねる。
        editor.setComposingOverlay(ranges)
        trace?.event(
            "boundary", "decorations.set",
            "requested", decorations.size,
            "drawn", ranges.size,
            "detail", ranges.joinToString(" ") { it.toString() }
        )
        readback(ranges)
    }

    /**
     * 次のフレームで、**重ねが本当にスパン列へ乗ったか**を数えて記録する。
     *
     * 画素を数えれば見えているかは分かるが、見えないとき
     * 「重ねが乗っていない」のか「乗ったが描かれていない」のかが区別できない。
     * `setHighlightTexts` を使っていた頃はまさにそこで詰まった
     * （ログは `drawn=1` なのに画面には 0 画素）ので、経路を移した今も同じ問いに答えられるようにしておく。
     */
    private fun readback(ranges: List<ComposingOverlay.Range>) {
        val trace = this.trace ?: return
        editor.post {
            val spans = editor.styles?.spans
            val lines = ranges.map { it.line }.distinct()
            var decorated = 0
            if (spans != null) {
                for (line in lines) {
                    // getSpansOnLine は行ごとに自分で鍵を取って返すので、moveToLine で握らない。
                    decorated += spans.read().getSpansOnLine(line).count {
                        it.getSpanExt<SpanColorResolver>(SpanExtAttrs.EXT_COLOR_RESOLVER) != null
                    }
                }
            }
            trace.event(
                "boundary", "decorations.readback",
                "wrapped", spans is ComposingOverlaySpans,
                "lines", lines.joinToString(","),
                "decorated", decorated,
                "visibleRows", editor.lastVisibleRow - editor.firstVisibleRow + 1
            )
        }
    }

    override fun clearComposingDecorations() {
        editor.clearComposingOverlay()
        trace?.event("boundary", "decorations.clear")
    }

    /**
     * 変換中の装飾の色。**色相は IME のものを使い、濃さ（alpha）は地の明るさから決め直す。**
     *
     * IME は入力先の背景を知らないまま色を選ぶので、そのまま通すと暗い配色で沈む。
     * 実測（Gboard）は変換対象が alpha `0x66`、それ以外が `0x19`。
     * 暗い地 `#2B2B2B` に `0x19` を重ねると `(46,57,56)` にしかならず、
     * 地の `(43,43,43)` とほぼ見分けが付かない ── **文節の塗り分けが消える**。
     *
     * どちらの文節かは IME が alpha の大小で表しているので、そこだけ読み取って、
     * 実際の濃さは配色ごとの値に置き換える。
     *
     * 色が来なかったときは配色の「強調の地」を引く。**枠は描かない** ──
     * スパンの経路に枠が無いため（[ComposingOverlay] の説明を見ること）。
     */
    private fun background(color: Int): Int {
        if (color == ComposingDecoration.COLOR_UNSPECIFIED) {
            return editor.colorScheme.getColor(EditorColorScheme.TEXT_HIGHLIGHT_BACKGROUND)
        }
        val active = ((color ushr 24) and 0xFF) >= ACTIVE_ALPHA_THRESHOLD
        val alpha = when {
            theme.isDark -> if (active) DARK_ACTIVE_ALPHA else DARK_INACTIVE_ALPHA
            else -> if (active) LIGHT_ACTIVE_ALPHA else LIGHT_INACTIVE_ALPHA
        }
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    // ------------------------------------------------------------------
    // 責務2: 変換1回を編集1単位にまとめる
    // ------------------------------------------------------------------

    override fun beginCompositionScope() {
        if (scopeOpen) return
        scopeOpen = true
        trace?.event("boundary", "scope.begin", "contentBatchBefore", editor.text.isInBatchEdit)
        // Undo の単位は Content の batch edit が 0 段に戻った時に切れる（text/Content.java:709）。
        // 境界が1段握り続ける限り、composing 中の全更新が1つの MultiAction にまとまる。
        editor.text.beginBatchEdit()
    }

    override fun endCompositionScope() {
        if (!scopeOpen) return
        scopeOpen = false
        editor.text.endBatchEdit()
        // ここで Content のネストが 0 に戻ったなら、Undo の単位が切れた瞬間。
        trace?.event("boundary", "scope.end", "contentBatchAfter", editor.text.isInBatchEdit)
    }

    override fun notifySelectionToIme() {
        // batch edit を握ると EditorInputConnection.endBatchEdit の中の updateSelection()
        // （widget/EditorInputConnection.java:405-408）が呼ばれなくなる。その代わりに出す。
        editor.notifySelectionToIme()
        trace?.event("boundary", "notifySelection", "cursor", editor.cursor.left)
    }

    // ------------------------------------------------------------------
    // 責務3: 変換中はエディタのキーバインドを黙らせる
    // ------------------------------------------------------------------

    override fun setKeyBindingsEnabled(enabled: Boolean) {
        editor.setKeyBindingsEnabled(enabled)
        trace?.event("boundary", "keyBindings", "enabled", enabled)
    }

    // ------------------------------------------------------------------
    // 責務4: 物理キーボードでも composing 経路を使わせる
    // ------------------------------------------------------------------

    override fun setSoftKeyboardSuppressedWithHardwareKeyboard(suppressed: Boolean) {
        editor.setDisableSoftKbdIfHardKbdAvailable(suppressed)
    }

    // ------------------------------------------------------------------
    // 状態
    // ------------------------------------------------------------------

    /** composing しているかはエンジンが持つ値をそのまま返す（二重に持たない）。 */
    override fun isComposing(): Boolean = editor.hasComposingText()

    override fun composingRange(): TextRange? {
        if (!editor.hasComposingText()) return null
        if (composingStart < 0 || composingEnd < composingStart) return null
        val content = editor.text
        val length = content.length
        if (composingEnd > length) return null
        val start = content.indexer.getCharPosition(composingStart)
        val end = content.indexer.getCharPosition(composingEnd)
        return TextRange(
            TextPosition(start.line, start.column, start.index),
            TextPosition(end.line, end.column, end.index)
        )
    }

    internal fun noteComposingRange(start: Int, end: Int) {
        composingStart = start
        composingEnd = end
    }

    internal fun forgetComposingRange() {
        composingStart = -1
        composingEnd = -1
    }

    private companion object {
        /** これ以上の alpha を「変換対象の文節」と読む。実測は 0x66 と 0x19 で、間に十分な開きがある。 */
        const val ACTIVE_ALPHA_THRESHOLD = 0x40

        const val LIGHT_ACTIVE_ALPHA = 0x66
        const val LIGHT_INACTIVE_ALPHA = 0x22
        const val DARK_ACTIVE_ALPHA = 0x99
        const val DARK_INACTIVE_ALPHA = 0x4D
    }
}
