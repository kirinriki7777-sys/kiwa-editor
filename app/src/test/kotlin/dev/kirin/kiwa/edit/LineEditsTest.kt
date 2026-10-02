package dev.kirin.kiwa.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LineEdits] の入力と出力を表形式で機械的に検証する。
 *
 * 記法は表と同じ ── `|` はカーソル、`[` `]` は選択範囲、`⏎` は改行。
 * [parse] / [render] がこの記法と `(text, selStart, selEnd)` を相互に変換するので、
 * 各テストは表の行をほぼそのまま書き写せる。
 */
class LineEditsTest {

    // ------------------------------------------------------------------
    // 記法 ── `|` / `[` `]` / `⏎`
    // ------------------------------------------------------------------

    private fun parse(spec: String): Triple<String, Int, Int> {
        val raw = spec.replace('⏎', '\n')
        val bar = raw.indexOf('|')
        if (bar >= 0) {
            val text = raw.removeRange(bar, bar + 1)
            return Triple(text, bar, bar)
        }
        val open = raw.indexOf('[')
        val close = raw.indexOf(']')
        require(open >= 0 && close > open) { "記法が読めない: $spec" }
        val withoutOpen = raw.removeRange(open, open + 1)
        val closeAdjusted = close - 1
        val text = withoutOpen.removeRange(closeAdjusted, closeAdjusted + 1)
        return Triple(text, open, closeAdjusted)
    }

    private fun render(text: String, selStart: Int, selEnd: Int): String {
        val marked = if (selStart == selEnd) {
            text.substring(0, selStart) + "|" + text.substring(selStart)
        } else {
            text.substring(0, selStart) + "[" + text.substring(selStart, selEnd) + "]" + text.substring(selEnd)
        }
        return marked.replace('\n', '⏎')
    }

    private fun apply(text: String, edit: Edit): String {
        val result = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
        return render(result, edit.selectionStart, edit.selectionEnd)
    }

    // ------------------------------------------------------------------
    // コメントのトグル（`//`）
    // ------------------------------------------------------------------

    private fun comment(spec: String, marker: String? = "//"): String {
        val (text, s, e) = parse(spec)
        val edit = LineEdits.toggleComment(text, s, e, marker) ?: return "null"
        return apply(text, edit)
    }

    @Test
    fun `C1 行頭でなく最初の非空白の位置に付ける`() {
        assertEquals("// a|bc", comment("a|bc"))
    }

    @Test
    fun `C2 字下げの後ろに付ける`() {
        assertEquals("    // a|bc", comment("    a|bc"))
    }

    @Test
    fun `C3 外す 空白1つまで消す`() {
        assertEquals("ab|c", comment("// ab|c"))
    }

    @Test
    fun `C4 空白が無くても外せる`() {
        assertEquals("ab|c", comment("//ab|c"))
    }

    @Test
    fun `C5 選択に触れている行を全部`() {
        assertEquals("[// abc⏎// def]", comment("[abc⏎def]"))
    }

    @Test
    fun `C6 全部コメント済みなら全部外す`() {
        assertEquals("[abc⏎def]", comment("[// abc⏎// def]"))
    }

    @Test
    fun `C7 一部だけコメント済みなら全部にもう1段付ける`() {
        assertEquals("[// // abc⏎// def]", comment("[// abc⏎def]"))
    }

    @Test
    fun `C8 空行にも付ける`() {
        assertEquals("[// abc⏎// ⏎// def]", comment("[abc⏎⏎def]"))
    }

    @Test
    fun `C8を戻すと空行が復元される`() {
        // 外すときに揃うのは C8 の note の通り ── 空行だった行は空行へ戻る。
        assertEquals("[abc⏎⏎def]", comment("[// abc⏎// ⏎// def]"))
    }

    @Test
    fun `C9 選択が行をまたぐ`() {
        assertEquals("// ab[c⏎// de]f", comment("ab[c⏎de]f"))
    }

    @Test
    fun `C10 各行の最初の非空白に付ける 揃えない`() {
        assertEquals("[  // a⏎    // b]", comment("[  a⏎    b]"))
    }

    @Test
    fun `行コメントを持たない言語は null`() {
        val (text, s, e) = parse("a|bc")
        assertNull(LineEdits.toggleComment(text, s, e, null))
    }

    @Test
    fun `カーソルがちょうど字下げの直後にあると付けても位置は動かない`() {
        // 挿入位置ちょうどに乗っていた境界は挿入の前に残る（C5 の全選択と同じ理屈）。
        assertEquals("    |// abc", comment("    |abc"))
    }

    @Test
    fun `カーソルが記号の直前にあると外した後は行頭に残る`() {
        assertEquals("|abc", comment("|// abc"))
    }

    // ------------------------------------------------------------------
    // 字下げ（unit = "    "）
    // ------------------------------------------------------------------

    private val unit = "    "

    private fun indent(spec: String, unit: String = this.unit): String {
        val (text, s, e) = parse(spec)
        return apply(text, LineEdits.indent(text, s, e, unit))
    }

    @Test
    fun `I1 選択が無ければカーソルのある行`() {
        assertEquals("    a|bc", indent("a|bc"))
    }

    @Test
    fun `I2 選択している行を全部`() {
        assertEquals("[    abc⏎    def]", indent("[abc⏎def]"))
    }

    @Test
    fun `I3 空行も字下げする`() {
        assertEquals("[    abc⏎    ⏎    def]", indent("[abc⏎⏎def]"))
    }

    @Test
    fun `字下げはタブでも同じ式で動く`() {
        assertEquals("\ta|bc", indent("a|bc", "\t"))
    }

    @Test
    fun `3行の選択でも両端の位置が正しい`() {
        assertEquals("[    one⏎    two⏎    three]", indent("[one⏎two⏎three]"))
    }

    // ------------------------------------------------------------------
    // 逆字下げ（unit = "    "）
    // ------------------------------------------------------------------

    private fun outdent(spec: String, unit: String = this.unit): String {
        val (text, s, e) = parse(spec)
        val edit = LineEdits.outdent(text, s, e, unit) ?: return "null"
        return apply(text, edit)
    }

    @Test
    fun `O1 一致すればまるごと1段消す`() {
        assertEquals("a|bc", outdent("    a|bc"))
    }

    @Test
    fun `O2 1段に満たない空白も全部消す`() {
        assertEquals("a|bc", outdent("  a|bc"))
    }

    @Test
    fun `O3 タブ1つも1段として消す`() {
        assertEquals("a|bc", outdent("\ta|bc"))
    }

    @Test
    fun `O4 戻すものが無ければ null`() {
        assertEquals("null", outdent("a|bc"))
    }

    @Test
    fun `O5 戻せる行だけ戻す`() {
        assertEquals("[abc⏎def]", outdent("[    abc⏎def]"))
    }

    @Test
    fun `unitより深い字下げは1段だけ消す`() {
        assertEquals("  a|bc", outdent("      a|bc"))
    }

    @Test
    fun `両端とも戻せる複数行は選択の両端が動く`() {
        assertEquals("[abc⏎def]", outdent("[    abc⏎    def]"))
    }

    // ------------------------------------------------------------------
    // 行の上下移動
    // ------------------------------------------------------------------

    private fun moveUp(spec: String): String {
        val (text, s, e) = parse(spec)
        val edit = LineEdits.moveLineUp(text, s, e) ?: return "null"
        return apply(text, edit)
    }

    private fun moveDown(spec: String): String {
        val (text, s, e) = parse(spec)
        val edit = LineEdits.moveLineDown(text, s, e) ?: return "null"
        return apply(text, edit)
    }

    @Test
    fun `M1 上へ動かすとカーソルの行内位置は保たれる`() {
        assertEquals("d|ef⏎abc", moveUp("abc⏎d|ef"))
    }

    @Test
    fun `M2 先頭行では上へ動かせない`() {
        assertEquals("null", moveUp("a|bc⏎def"))
    }

    @Test
    fun `M3 末尾行では下へ動かせない`() {
        assertEquals("null", moveDown("abc⏎d|ef"))
    }

    @Test
    fun `M4 下へ動かすと選択されたまま`() {
        assertEquals("ghi⏎[abc⏎def]", moveDown("[abc⏎def]⏎ghi"))
    }

    @Test
    fun `下へ動かしてもカーソルの行内位置は保たれる`() {
        assertEquals("def⏎a|bc", moveDown("a|bc⏎def"))
    }

    @Test
    fun `3行のうち下2行を上へ動かす`() {
        assertEquals("[two⏎three]⏎one", moveUp("one⏎[two⏎three]"))
    }

    @Test
    fun `M5 末尾に改行が無いファイルで末尾行を上へ動かしても改行の数が変わらない`() {
        val text = "abc\ndef"
        val edit = LineEdits.moveLineUp(text, 4, 4)!!
        val result = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
        assertEquals("def\nabc", result)
        assertEquals(text.count { it == '\n' }, result.count { it == '\n' })
    }

    @Test
    fun `M6 CRLFのファイルで上へ動かしてもCRLFのまま`() {
        val text = "one\r\ntwo\r\nthree"
        // カーソルは "three"（末尾行）に置いて上へ動かす。
        val caret = text.indexOf("three")
        val edit = LineEdits.moveLineUp(text, caret, caret)!!
        val result = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
        assertEquals("one\r\nthree\r\ntwo", result)
        assertTrue("\\r が裸で残っている", !result.contains(Regex("\r(?!\n)")))
        assertEquals(2, Regex("\r\n").findAll(result).count())
    }

    @Test
    fun `CRLFのファイルで下へ動かしてもCRLFのまま`() {
        val text = "one\r\ntwo\r\nthree"
        val caret = text.indexOf("one")
        val edit = LineEdits.moveLineDown(text, caret, caret)!!
        val result = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
        assertEquals("two\r\none\r\nthree", result)
        assertEquals(2, Regex("\r\n").findAll(result).count())
    }

    // ★ 受入で見つけた欠陥の回帰試験（2026-09-08 / リーダー）
    //
    // 発注文の表には「末尾に改行が**無い**ファイル」（M5）しか書いていなかったので、
    // 逆の側 ── **ふつうのテキストファイル**が抜けていた。末尾の改行が作る空の行を
    // 実在の行として動かすと、改行が1つ消えてファイル末尾の改行が失われる。

    @Test
    fun `M7 末尾に改行があるファイルの最後の実行行は下へ動かせない`() {
        val text = "abc\ndef\n"
        assertNull(LineEdits.moveLineDown(text, text.indexOf("def"), text.indexOf("def")))
    }

    @Test
    fun `M8 末尾の改行が作る空の行は上へ動かせない`() {
        val text = "abc\ndef\n"
        assertNull(LineEdits.moveLineUp(text, text.length, text.length))
    }

    @Test
    fun `M9 CRLFでも末尾の改行は失われない`() {
        val text = "abc\r\ndef\r\n"
        assertNull(LineEdits.moveLineDown(text, text.indexOf("def"), text.indexOf("def")))
        // 上へは動かせる。そのとき改行の数も種類も変わらない。
        val up = LineEdits.moveLineUp(text, text.indexOf("def"), text.indexOf("def"))!!
        val moved = text.substring(0, up.start) + up.replacement + text.substring(up.end)
        assertEquals("def\r\nabc\r\n", moved)
        assertEquals(2, Regex("\r\n").findAll(moved).count())
    }

    @Test
    fun `M10 末尾に改行がある3行のファイルで真ん中を下へ動かせる`() {
        // 「動かせない」を足したせいで動くべきものまで止めていないか。
        val text = "one\ntwo\nthree\n"
        val caret = text.indexOf("two")
        val edit = LineEdits.moveLineDown(text, caret, caret)!!
        val moved = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
        assertEquals("one\nthree\ntwo\n", moved)
    }

    // ------------------------------------------------------------------
    // smart home
    // ------------------------------------------------------------------

    private fun home(spec: String): String {
        val (text, caret, _) = parse(spec)
        val moved = LineEdits.smartHome(text, caret)
        return render(text, moved, moved)
    }

    @Test
    fun `S1 字下げの直後へ`() {
        assertEquals("    |abc", home("    ab|c"))
    }

    @Test
    fun `S2 もう一度で行頭へ`() {
        assertEquals("|    abc", home("    |abc"))
    }

    @Test
    fun `S3 行頭からは字下げの直後へ`() {
        assertEquals("    |abc", home("|    abc"))
    }

    @Test
    fun `S4 字下げが無ければ行頭へ`() {
        assertEquals("|abc", home("abc|"))
    }

    @Test
    fun `S5 空白だけの行は行頭へ`() {
        assertEquals("|    ", home("    |"))
    }

    @Test
    fun `S6 空行は動かない`() {
        assertEquals("|", home("|"))
    }

    @Test
    fun `タブで字下げした行も同じ式で動く`() {
        assertEquals("\t|abc", home("\tabc|"))
    }

    @Test
    fun `空白とタブが混ざっても同じ式で動く`() {
        assertEquals(" \t|abc", home(" \tabc|"))
    }

    // ------------------------------------------------------------------
    // ★ 状態を跨ぐ
    // ------------------------------------------------------------------

    @Test
    fun `X1 コメント を もう一度コメント で元に戻る`() {
        val (text, s, e) = parse("a|bc")
        val once = LineEdits.toggleComment(text, s, e, "//")!!
        val afterOnce = text.substring(0, once.start) + once.replacement + text.substring(once.end)
        val twice = LineEdits.toggleComment(afterOnce, once.selectionStart, once.selectionEnd, "//")!!
        val afterTwice = afterOnce.substring(0, twice.start) + twice.replacement + afterOnce.substring(twice.end)
        assertEquals(text, afterTwice)
        assertEquals(s, twice.selectionStart)
        assertEquals(e, twice.selectionEnd)
    }

    @Test
    fun `X2 字下げ を 逆字下げ で元に戻る 選択範囲も`() {
        val (text, s, e) = parse("[abc⏎def]")
        val indented = LineEdits.indent(text, s, e, unit)
        val afterIndent = text.substring(0, indented.start) + indented.replacement + text.substring(indented.end)
        val outdented = LineEdits.outdent(afterIndent, indented.selectionStart, indented.selectionEnd, unit)!!
        val afterOutdent =
            afterIndent.substring(0, outdented.start) + outdented.replacement + afterIndent.substring(outdented.end)
        assertEquals(text, afterOutdent)
        assertEquals(s, outdented.selectionStart)
        assertEquals(e, outdented.selectionEnd)
    }

    @Test
    fun `X3 行を上へ を 行を下へ で元に戻る 文字列も選択も`() {
        val (text, s, e) = parse("abc⏎d|ef")
        val up = LineEdits.moveLineUp(text, s, e)!!
        val afterUp = text.substring(0, up.start) + up.replacement + text.substring(up.end)
        val down = LineEdits.moveLineDown(afterUp, up.selectionStart, up.selectionEnd)!!
        val afterDown = afterUp.substring(0, down.start) + down.replacement + afterUp.substring(down.end)
        assertEquals(text, afterDown)
        assertEquals(s, down.selectionStart)
        assertEquals(e, down.selectionEnd)
    }

    @Test
    fun `X4 smart home を2回で元の位置へ戻る`() {
        // 往復するのは「字下げの直後」と「行頭」の2点（S2→S3→S2）。
        // 3点目（S1 の caret のような、どちらでもない位置）は往路の入口であって輪の中ではない。
        val (text, caret, _) = parse("    |abc")
        val once = LineEdits.smartHome(text, caret)
        val twice = LineEdits.smartHome(text, once)
        assertEquals(caret, twice)
    }

    @Test
    fun `X5 コメント 字下げ 逆字下げ コメント で元に戻る`() {
        val original = "abc"
        val commented = LineEdits.toggleComment(original, 0, 0, "//")!!
        val afterComment = original.substring(0, commented.start) + commented.replacement +
            original.substring(commented.end)

        val indented = LineEdits.indent(afterComment, commented.selectionStart, commented.selectionStart, unit)
        val afterIndent =
            afterComment.substring(0, indented.start) + indented.replacement + afterComment.substring(indented.end)

        val outdented =
            LineEdits.outdent(afterIndent, indented.selectionStart, indented.selectionStart, unit)!!
        val afterOutdent =
            afterIndent.substring(0, outdented.start) + outdented.replacement + afterIndent.substring(outdented.end)

        val uncommented =
            LineEdits.toggleComment(afterOutdent, outdented.selectionStart, outdented.selectionStart, "//")!!
        val afterUncomment = afterOutdent.substring(0, uncommented.start) + uncommented.replacement +
            afterOutdent.substring(uncommented.end)

        assertEquals(original, afterUncomment)
    }

    @Test
    fun `X6 コメントは選択が空でも選択ありでも同じ関数で扱える`() {
        val cursorOnly = comment("a|bc")
        val wholeLineSelected = comment("[abc]")
        // どちらも同じコメントが付く ── 表す記法が違うだけ。
        assertEquals("// abc", cursorOnly.replace("|", ""))
        assertEquals("// abc", wholeLineSelected.replace("[", "").replace("]", ""))
    }

    @Test
    fun `X6 字下げは選択が空でも選択ありでも同じ関数で扱える`() {
        assertEquals("    abc", indent("a|bc").replace("|", ""))
        assertEquals("    abc", indent("[abc]").replace("[", "").replace("]", ""))
    }

    @Test
    fun `X6 行移動は選択が空でも選択ありでも同じ関数で扱える`() {
        assertEquals("def⏎abc", moveUp("abc⏎[def]").replace("[", "").replace("]", ""))
        assertEquals("def⏎abc", moveUp("abc⏎d|ef").replace("|", ""))
    }
}
