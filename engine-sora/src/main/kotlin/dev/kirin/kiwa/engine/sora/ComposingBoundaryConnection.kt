package dev.kirin.kiwa.engine.sora

import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute

/**
 * IME とエディタの間に挟む [InputConnection]。**いつ境界を働かせるか**を決める側。
 *
 * ## batch edit の所有権をここで移す
 *
 * IME は未確定文字列を更新するたびに `beginBatchEdit` / `endBatchEdit` を開いて閉じる
 * （実測: 1セッションで 614 回ずつ）。Sora の `Content` はネストが 0 に戻った時に
 * Undo の単位を切るので（text/Content.java:709）、**変換候補を1つ選ぶたびに Undo 単位が積まれる**。
 *
 * そこで **IME の begin/end は `Content` へ一切流さず**、境界が段数を1段だけ持つ。
 * 「IME が batch edit を開いている」か「composing 中」のどちらかである限り握り続け、
 * 両方とも終わったときに1回だけ閉じる。
 *
 * **握ると IME への通知が止まる。** `EditorInputConnection.endBatchEdit()` は
 * ネストが 0 になった時に `editor.updateSelection()` を呼んでおり
 * （widget/EditorInputConnection.java:405-408）、それが `InputMethodManager.updateSelection()` で
 * カーソル位置と変換範囲を IME へ渡している（widget/CodeEditor.java:4493）。
 * だから IME の `endBatchEdit` を受けたら、代わりに自分で通知を出す。
 *
 * ## Sora 自身の batch edit とは別物
 *
 * Sora は `setComposingText` の初回に自分で `beginBatchEdit()` を呼び、`finishComposingText` で
 * 閉じる（widget/EditorInputConnection.java:438, 489）。それは Sora の内部呼び出しなので
 * この wrapper を通らず、Sora の中で釣り合っている。ここで数えるのは **IME から来た分だけ**。
 */
internal class ComposingBoundaryConnection(
    target: InputConnection,
    private val editor: KiwaCodeEditor,
    private val boundary: SoraImeBoundary
) : InputConnectionWrapper(target, true) {

    /** IME が開いたまま閉じていない batch edit の段数。 */
    private var imeBatchDepth = 0

    /** 未確定文字列を保持している間 true。 */
    private var composing = false

    private val trace: ImeTrace? = boundary.trace

    /**
     * 呼び出しの enter / exit を `phase` と `call` で対にする。
     * `tools/analyze-composing-paths.py` がこの名前を使って呼び出しを対応付ける。
     *
     * **記録のために InputConnection へ問い合わせない。** 呼ばれた引数と戻り値だけを書き、
     * 計測が IME のプロトコルを変えるのを避ける。
     */
    private fun enter(event: String, vararg fields: Any?): Long {
        val trace = this.trace ?: return -1
        val call = trace.nextCall()
        trace.event("sora", event, "phase", "enter", "call", call, *fields)
        return call
    }

    private fun exit(event: String, call: Long, result: Boolean): Boolean {
        trace?.event("sora", event, "phase", "exit", "call", call, "result", result)
        return result
    }

    // ------------------------------------------------------------------
    // batch edit
    // ------------------------------------------------------------------

    override fun beginBatchEdit(): Boolean {
        val call = enter("IC.beginBatchEdit", "imeDepthBefore", imeBatchDepth)
        imeBatchDepth++
        syncScope()
        return exit("IC.beginBatchEdit", call, true)
    }

    override fun endBatchEdit(): Boolean {
        val call = enter("IC.endBatchEdit", "imeDepthBefore", imeBatchDepth, "composing", composing)
        if (imeBatchDepth > 0) imeBatchDepth--
        syncScope()
        // Sora はネストが 0 になった時だけ通知していた。その条件をそのまま引き継ぐ。
        if (imeBatchDepth == 0) {
            boundary.notifySelectionToIme()
        }
        return exit("IC.endBatchEdit", call, editor.text.isInBatchEdit)
    }

    private fun syncScope() {
        if (composing || imeBatchDepth > 0) {
            boundary.beginCompositionScope()
        } else {
            boundary.endCompositionScope()
        }
    }

    // ------------------------------------------------------------------
    // composing
    // ------------------------------------------------------------------

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val call = enter(
            "IC.setComposingText",
            "text", TraceText.preview(text),
            "newCursorPosition", newCursorPosition,
            "spans", TraceText.spans(text),
            "hasTextAttribute", false
        )
        beforeComposingEdit()
        val handled = editor.duringComposingText { super.setComposingText(text, newCursorPosition) }
        afterComposingEdit(text)
        return exit("IC.setComposingText", call, handled)
    }

    override fun setComposingText(
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?
    ): Boolean {
        // Sora は3引数版のオーバーロードを1つも実装していない（S1a）。ここで受けて2引数版へ
        // 落とす。装飾は TextAttribute ではなく CharSequence のスパンで来るので、
        // どちらの経路で来ても取りこぼさない。
        val call = enter(
            "IC.setComposingText",
            "text", TraceText.preview(text),
            "newCursorPosition", newCursorPosition,
            "spans", TraceText.spans(text),
            "hasTextAttribute", textAttribute != null
        )
        beforeComposingEdit()
        val handled = editor.duringComposingText { super.setComposingText(text, newCursorPosition, textAttribute) }
        afterComposingEdit(text)
        return exit("IC.setComposingText", call, handled)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val call = enter("IC.setComposingRegion", "start", start, "end", end, "hasTextAttribute", false)
        beforeComposingEdit()
        val handled = super.setComposingRegion(start, end)
        if (!editor.hasComposingText()) endComposing()
        return exit("IC.setComposingRegion", call, handled)
    }

    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean {
        val call = enter(
            "IC.setComposingRegion",
            "start", start, "end", end, "hasTextAttribute", textAttribute != null
        )
        beforeComposingEdit()
        val handled = super.setComposingRegion(start, end, textAttribute)
        if (!editor.hasComposingText()) endComposing()
        return exit("IC.setComposingRegion", call, handled)
    }

    override fun finishComposingText(): Boolean {
        val call = enter("IC.finishComposingText", "composing", composing)
        val handled = super.finishComposingText()
        endComposing()
        return exit("IC.finishComposingText", call, handled)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val call = enter(
            "IC.commitText",
            "text", TraceText.preview(text),
            "newCursorPosition", newCursorPosition,
            "spans", TraceText.spans(text),
            "hasTextAttribute", false
        )
        if (isEmptyCommit(text)) return exit("IC.commitText", call, true)
        val handled = super.commitText(text, newCursorPosition)
        if (!editor.hasComposingText()) endComposing()
        return exit("IC.commitText", call, handled)
    }

    override fun commitText(
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?
    ): Boolean {
        val call = enter(
            "IC.commitText",
            "text", TraceText.preview(text),
            "newCursorPosition", newCursorPosition,
            "spans", TraceText.spans(text),
            "hasTextAttribute", textAttribute != null
        )
        if (isEmptyCommit(text)) return exit("IC.commitText", call, true)
        val handled = super.commitText(text, newCursorPosition, textAttribute)
        if (!editor.hasComposingText()) endComposing()
        return exit("IC.commitText", call, handled)
    }

    /**
     * **中身の無い確定は通さない。**
     *
     * Gboard は変換を確定した直後、締めくくりにもう1回 `commitText("")` を出す
     * （2026-09-06 の実機ログ seq 638-642。`finishComposingText` の後なので composing は無い）。
     * これをそのまま Sora へ流すと「空文字を挿入する」編集として記録され、
     * **画面は何も変わらないのに Undo の単位が1つ積まれる** ──
     * 使う側からは「元に戻すの1回目が空振りする」形で出る。実際に踏んだ。
     *
     * 選択があるときの `commitText("")` は**選択の削除**という意味を持つので、そこは通す。
     * 塞ぐのは「composing も選択も無いところへ空文字を置く」＝定義上の無操作だけ。
     */
    private fun isEmptyCommit(text: CharSequence?): Boolean {
        if (!EmptyCommit.isNoOp(text, editor.hasComposingText(), editor.cursor.isSelected)) return false
        trace?.event("boundary", "commit.emptySkipped")
        return true
    }

    override fun closeConnection() {
        endComposing()
        imeBatchDepth = 0
        syncScope()
        super.closeConnection()
    }

    /** 編集より前に段を開けておかないと、その編集が単位の外へこぼれる。 */
    private fun beforeComposingEdit() {
        if (composing) return
        composing = true
        // 責務3 — composing の間はエディタのキー処理を全部止める。個別に塞がないのは、
        // 測ったのが IME 2つ・試したキーの範囲だけで、他の IME が素通しする可能性が残るため。
        boundary.setKeyBindingsEnabled(false)
        syncScope()
    }

    private fun afterComposingEdit(text: CharSequence?) {
        if (!editor.hasComposingText()) {
            // 空文字を渡されると Sora は内部で finishComposingText() を呼ぶ
            // （widget/EditorInputConnection.java:475-478）。それは wrapper を通らないので、
            // 状態はエンジンに訊いて判断する。
            endComposing()
            return
        }
        val body = text ?: return
        // Sora はどの分岐を通ってもカーソルを composing の終端に置く
        // （insert / delete / replace / 変更なし の4分岐 = widget/EditorInputConnection.java:463-478）。
        // なので終端はカーソルから読める。範囲を自前で数え上げない。
        val end = editor.cursor.left
        val start = end - body.length
        if (start < 0) {
            boundary.clearComposingDecorations()
            boundary.forgetComposingRange()
            return
        }
        boundary.noteComposingRange(start, end)
        trace?.event("boundary", "composing.range", "start", start, "end", end)
        val decorations = ComposingDecorations.extract(body, start)
        if (decorations.isEmpty()) {
            boundary.clearComposingDecorations()
        } else {
            boundary.setComposingDecorations(decorations)
        }
    }

    private fun endComposing() {
        if (!composing) return
        composing = false
        boundary.clearComposingDecorations()
        boundary.forgetComposingRange()
        boundary.setKeyBindingsEnabled(true)
        syncScope()
    }
}

/**
 * 「その確定は何もしないか」の判定だけを切り出したもの。
 *
 * 分けてあるのは**試験を通すため** ── 実機で出るのは Gboard の気まぐれで、
 * 狙って再現できなかった（2026-09-06 に1回だけ観測し、その後2回試して出ていない）。
 * 塞ぐ条件そのものは3つの真偽値で決まるので、そこだけは機械で確かめられる。
 */
internal object EmptyCommit {

    fun isNoOp(text: CharSequence?, composing: Boolean, selected: Boolean): Boolean {
        if (!text.isNullOrEmpty()) return false
        // composing 中の空文字は「未確定を消して確定する」意味を持つ。通す。
        if (composing) return false
        // 選択中の空文字は「選択を消す」意味を持つ。通す。
        if (selected) return false
        return true
    }
}
