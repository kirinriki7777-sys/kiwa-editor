package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.text.ComposingText
import io.github.rosemoe.sora.text.Content

/**
 * 変換中に保存する本文（確定済みの文字だけで組んだ本文）を決める状態。**エディタに1組。**
 *
 * 持つのは3つだけ ── 未確定に置き換えられた元の文字 [original]、範囲の中に今あるはずの文字
 * [expected]、それを持つ `Content`（[owner]）。composing の範囲は Sora の `ComposingText` が
 * 本文の変化に合わせて自分でずらしているので、それを正本として毎回渡してもらい、ここでは写さない。
 * 本文・IME・Undo には書かない。計算するだけ。
 *
 * 呼ぶ所は wrapper の `setComposingText` の前後・`setComposingRegion` の前後と、保存の時。
 * 引数の `composing` は `editor.hasComposingText()`、`range` は Sora の `ComposingText`
 * （読めなかった時は null）。
 *
 * 上限: [original] は選択・再変換と範囲拡張で取り込んだ元の文字（最大で文書大）、[expected] は未確定範囲の長さ。
 * 選択が巨大で困ったら、[original] を持たずに「composing 中は保存不可」へ倒すのが逃げ道。
 */
internal class SettledComposing {

    private var owner: Content? = null
    private var original = ""
    private var expected: String? = null

    /** 変換中に安全を証明できなくなった。composing が終わるまで戻さない。 */
    private var lost = false

    /** `setComposingRegion` の前に確かめた範囲。呼出しの後で使う。 */
    private var regionStart = -1
    private var regionEnd = -1

    /** Sora を呼ぶ前。変換していなければ、置き換えられる元の文字は今の選択。 */
    fun beforeComposingText(
        content: Content, composing: Boolean, range: ComposingText?,
        selectionStart: Int, selectionEnd: Int
    ) {
        if (!composing) {
            clear()
            owner = content
            original = content.substring(selectionStart, selectionEnd)
        } else if (!verified(content, range)) {
            lost = true
        }
    }

    /** Sora を呼んだ後。候補を更新しても元の文字は変えない。失敗した呼出しは前の状態のまま確かめ直す。 */
    fun afterComposingText(
        content: Content, composing: Boolean, range: ComposingText?,
        handled: Boolean, text: CharSequence?
    ) {
        if (!composing) return clear()
        if (handled && !lost) expected = text?.toString()
        if (!verified(content, range)) lost = true
    }

    fun beforeComposingRegion(content: Content, composing: Boolean, range: ComposingText?) {
        regionStart = -1
        regionEnd = -1
        if (!composing) return clear()
        if (!verified(content, range)) {
            lost = true
            return
        }
        regionStart = range!!.startIndex
        regionEnd = range.endIndex
    }

    /**
     * 新しい範囲の中身はどれも確定済みの文字か、元を知っている未確定のどちらか。
     * 前の範囲を全部含むなら元の文字の左右に、新しく入った確定済みの文字を足す。
     * 前の未確定が1文字でも外へ出たなら、元の選択の置換は済んだものとし、新しく入った確定済みの文字だけを持つ。
     */
    fun afterComposingRegion(content: Content, composing: Boolean, range: ComposingText?, handled: Boolean) {
        if (!composing) return clear()
        if (!handled) {
            if (!verified(content, range)) lost = true
            return
        }
        if (lost) return
        if (range == null || !inBounds(content, range)) {
            lost = true
            return
        }
        val start = range.startIndex
        val end = range.endIndex
        original = when {
            // 変換していなかった。確定済みの文字をそのまま元の文字にする。
            owner == null -> content.substring(start, end)
            // ここから先は、呼ぶ前に owner と範囲 [regionStart, regionEnd) を確かめてある。
            start <= regionStart && regionEnd <= end ->
                content.substring(start, regionStart) + original + content.substring(regionEnd, end)
            else ->
                content.substring(start, maxOf(start, minOf(end, regionStart))) +
                    content.substring(minOf(end, maxOf(start, regionEnd)), end)
        }
        owner = content
        expected = content.substring(start, end)
    }

    /**
     * 保存する本文。変換していなければ表示本文。変換中は、範囲の中身が [expected] だと確かめられた時だけ
     * 範囲を [original] に戻した本文。確かめられなければ安全不明（lost）として変換終了まで null（保存不可）。
     * 表示本文へは倒さない。
     */
    fun settledText(content: Content, composing: Boolean, range: ComposingText?): String? {
        if (!composing) return content.toString()
        if (!verified(content, range)) {
            lost = true
            return null
        }
        return content.substring(0, range!!.startIndex) + original +
            content.substring(range.endIndex, content.length)
    }

    private fun verified(content: Content, range: ComposingText?): Boolean {
        val expected = this.expected ?: return false
        if (lost || owner !== content || range == null || !inBounds(content, range)) return false
        return content.substring(range.startIndex, range.endIndex) == expected
    }

    private fun clear() {
        owner = null
        original = ""
        expected = null
        lost = false
    }
}

/** `preSetComposing` の途中は範囲がまだ -1 のことがあるので、範囲なしと同じに扱う。 */
internal fun inBounds(content: Content, range: ComposingText): Boolean =
    !range.preSetComposing && range.startIndex in 0..range.endIndex && range.endIndex <= content.length
