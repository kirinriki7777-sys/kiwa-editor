package dev.kirin.kiwa.ui

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText

/**
 * **IME を通る入力欄**。パレットの欄（E10b）と検索の欄（E11）が同じ扱いをするための共通の口。
 *
 * ## なぜ共通にするか
 *
 * この端末では、どの入力欄も日本語 IME を通る。E10b の実機で分かったのは
 * **その扱いを間違えると欄そのものが使えなくなる**ということで、直した内容は
 * パレット固有ではない ── 検索欄でも同じ形で出る。
 *
 * 同じ扱いを2箇所に書くと必ずずれる（設定の適用の口を1本にしたのと同じ話）。
 * **`Editable` の扱いはここが1本で持つ。**
 *
 * ## 3つの決め（全部 E10b の実機で確かめた）
 *
 * 1. **変換中の文字を絞り込みや検索へ流さない** ── 「ほ」「ほz」「ほぞ」のたびに
 *    引き直すと、一覧が跳ねて選ぼうとした行が指の下から逃げる。[settledText] が確定した分だけを返す
 * 2. **変換中はキーを横取りしない** ── Enter も Esc も矢印も、変換中は IME のもの。
 *    [onSettledKeyDown] と [onEscape] は変換中に呼ばれない
 * 3. **`IME_FLAG_FORCE_ASCII` で殴らない** ── 日本語のファイル名も、日本語の検索語も要る。
 *    英数だけを前提にした欄は、日本語 IME が入っているだけで塞がる（`>` が全角の「＞」で入る）
 */
open class ImeAwareInput(context: Context) : EditText(context) {

    /**
     * Esc / BACK が来たとき（**変換中を除く**）。true を返すとそこで止める。
     *
     * Esc は変換の取り消しに使われるので、変換中は IME へ渡す。
     */
    var onEscape: (() -> Boolean)? = null

    /**
     * 変換中でないときのキー押下。true を返すとそこで止める。
     *
     * 矢印での選択移動や Enter での決定はここへ入れる ── 変換中に横取りすると
     * **変換そのものが確定できなくなる**。
     *
     * **修飾キーを見たい側があるので [KeyEvent] ごと渡す**（検索欄の Shift+Enter＝前を探す）。
     */
    var onSettledKeyDown: ((keyCode: Int, event: KeyEvent) -> Boolean)? = null

    init {
        setSingleLine(true)
        super.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (isComposing()) return@setOnKeyListener false
            onSettledKeyDown?.invoke(keyCode, event) ?: false
        }
    }

    /**
     * IME が未確定として持っている範囲。無ければ null。
     *
     * 未確定の印は `Editable` に付いたスパンで届く（`BaseInputConnection` が付ける）。
     * **打った文字を数えても分からない** ── 変換中かどうかは IME だけが知っている。
     */
    fun composingRange(): IntRange? {
        val content = text ?: return null
        val start = BaseInputConnection.getComposingSpanStart(content)
        val end = BaseInputConnection.getComposingSpanEnd(content)
        return if (start in 0 until end) start until end else null
    }

    fun isComposing(): Boolean = composingRange() != null

    /** 欄に入っている文字そのもの。**未確定の分も含む。** */
    fun rawText(): String = text?.toString() ?: ""

    /**
     * 確定した分だけ。**引くもの（絞り込み・検索）はこちらを使う。**
     *
     * 未確定のまま流すと、変換候補を選ぶたびに結果が跳ねる。
     */
    fun settledText(): String {
        val raw = rawText()
        val range = composingRange() ?: return raw
        return raw.removeRange(range.first, range.last + 1)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val closing = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
        // **変換中は IME のもの。** Esc は変換の取り消しに使われる。
        if (closing && !isComposing() && event.action == KeyEvent.ACTION_UP) {
            if (onEscape?.invoke() == true) return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
