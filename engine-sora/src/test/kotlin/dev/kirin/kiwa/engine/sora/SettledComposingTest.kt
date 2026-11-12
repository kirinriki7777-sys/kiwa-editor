package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.text.ComposingText
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 変換中に保存する本文（[SettledComposing]）の行動試験。C 番号は
 * 設計の時に付けた場合分けの番号（C11 は null を返す形に変更した）。
 *
 * 本文と範囲は Sora の本物の `Content` と `ComposingText` で持つ。範囲のずれは、Sora の
 * `EditorInputConnection` の購読（javap `lambda$new$0`）と同じく、`Content` の変更通知から
 * `shiftOnInsert` / `shiftOnDelete` を呼んで作る。
 *
 * `EditorInputConnection` そのものは `View` が要って JVM で動かないので、[Ime] は
 * Sora 0.24.6 の `minimizeComposingTextUpdate` や `trackComposingTextOnCommit` の最適化分岐を省き、
 * `setComposingText` / `setComposingRegion` / `commitText` / `finishComposingText` が
 * `Content` と `ComposingText` にもたらす本文と範囲の結果を再現した fixture。
 * 前後で [SettledComposing] を呼ぶ形は、後で wrapper に入れる配線と同じ。
 */
class SettledComposingTest {

    private class Ime(text: String, cursor: Int = text.length) {
        var content = Content(text)
            private set
        // 本体の onCreateInputConnection/reset と同じ初期状態（既定の0,0は変換中判定になる）。
        val range = ComposingText().apply { reset() }
        var left = cursor
        var right = cursor
        val state = SettledComposing()

        /** 旧方式の再現（53faf28 の `noteComposingRange` と `MainActivity.settledText`）。比較のためだけに持つ。 */
        private var oldStart = -1
        private var oldEnd = -1

        init {
            listen(content)
        }

        private fun listen(target: Content) {
            target.addContentListener(object : ContentListener {
                override fun beforeReplace(content: Content) {}
                override fun afterInsert(
                    content: Content, startLine: Int, startColumn: Int,
                    endLine: Int, endColumn: Int, inserted: CharSequence
                ) {
                    val start = content.indexer.getCharIndex(startLine, startColumn)
                    range.shiftOnInsert(start, start + inserted.length)
                }

                override fun afterDelete(
                    content: Content, startLine: Int, startColumn: Int,
                    endLine: Int, endColumn: Int, deleted: CharSequence
                ) {
                    val start = content.indexer.getCharIndex(startLine, startColumn)
                    range.shiftOnDelete(start, start + deleted.length)
                }
            })
        }

        val composing get() = range.isComposing

        fun select(start: Int, end: Int) {
            left = start
            right = end
        }

        fun setComposingText(text: String): Boolean {
            state.beforeComposingText(content, composing, range, left, right)
            val handled = soraSetComposingText(text)
            state.afterComposingText(content, composing, range, handled, text)
            if (composing) {
                oldStart = left - text.length
                oldEnd = left
            } else {
                forgetOld()
            }
            return handled
        }

        fun setComposingRegion(start: Int, end: Int): Boolean {
            state.beforeComposingRegion(content, composing, range)
            val handled = soraSetComposingRegion(start, end)
            state.afterComposingRegion(content, composing, range, handled)
            if (!composing) forgetOld()
            return handled
        }

        fun commitText(text: String) {
            if (composing) {
                content.replace(range.startIndex, range.endIndex, text)
                left = range.endIndex
                range.reset()
            } else {
                deleteSelected()
                insert(left, text)
                left += text.length
            }
            right = left
            forgetOld()
        }

        fun finishComposingText() {
            range.reset()
            forgetOld()
        }

        /** タブの切替。Sora は `restartInput` → `reset()` で composing を捨てる。 */
        fun switchTo(text: String, cursor: Int = text.length) {
            range.reset()
            forgetOld()
            content = Content(text)
            listen(content)
            select(cursor, cursor)
        }

        /** IME を通らないアプリの編集（行編集・置換など）。 */
        fun appInsert(index: Int, text: String) = insert(index, text)

        fun appDelete(start: Int, end: Int) = content.delete(start, end)

        fun settled(): String? = state.settledText(content, composing, range)

        fun old(): String {
            val text = content.toString()
            if (!composing || oldStart < 0) return text
            return text.removeRange(oldStart.coerceIn(0, text.length), oldEnd.coerceIn(0, text.length))
        }

        private fun forgetOld() {
            oldStart = -1
            oldEnd = -1
        }

        private fun insert(index: Int, text: String) {
            val at = content.indexer.getCharPosition(index)
            content.insert(at.line, at.column, text)
        }

        private fun deleteSelected() {
            if (left != right) content.delete(left, right)
            right = left
        }

        /** javap `EditorInputConnection.setComposingText`（改行入り拒否・初回・候補更新・空文字で終了）。 */
        private fun soraSetComposingText(text: String): Boolean {
            if (text.contains('\n')) return false
            if (!range.isComposing) {
                range.preSetComposing = true
                deleteSelected()
                insert(left, text)
                left += text.length
                right = left
                range.preSetComposing = false
                range.set(left - text.length, left)
            } else {
                content.replace(range.startIndex, range.endIndex, text)
                range.adjustLength(text.length)
                left = range.endIndex
                right = left
            }
            if (text.isEmpty()) range.reset()
            return true
        }

        /** javap `EditorInputConnection.setComposingRegion`（空範囲で終了・入替え・切詰め・空なら拒否）。 */
        private fun soraSetComposingRegion(start: Int, end: Int): Boolean {
            if (start == end) {
                range.reset()
                return true
            }
            var s = minOf(start, end)
            var e = maxOf(start, end)
            if (s < 0) s = 0
            if (e > content.length) e = content.length
            if (s >= e) return false
            range.set(s, e)
            return true
        }
    }

    /** `ABC` の `B` を選び、未確定 `x` にした所（C2）。 */
    private fun replacingB() = Ime("ABC").apply {
        select(1, 2)
        setComposingText("x")
    }

    // ------------------------------------------------------------------
    // 1-6: 期待本文
    // ------------------------------------------------------------------

    @Test
    fun `C1 挿入した読みは保存しない`() {
        val ime = Ime("ABC")
        ime.setComposingText("てすと")
        assertEquals("ABCてすと", ime.content.toString())
        assertEquals("ABC", ime.settled())
    }

    @Test
    fun `C2 C3 選択を置き換えた読みは候補を変えても元の文字で保存する`() {
        val ime = replacingB()
        assertEquals("ABC", ime.settled())
        ime.setComposingText("xy")
        assertEquals("ABC", ime.settled())
        ime.setComposingText("XY")
        assertEquals("AXYC", ime.content.toString())
        assertEquals("ABC", ime.settled())
    }

    @Test
    fun `C4 領域を後ろの1文字へ縮めると前の文字は確定する`() {
        val ime = replacingB()
        ime.setComposingText("xy")
        ime.setComposingRegion(2, 3)
        assertEquals(2, ime.range.startIndex)
        assertEquals(3, ime.range.endIndex)
        assertEquals("AxC", ime.settled())
    }

    @Test
    fun `C5 確定してから次の読みを始めても同じ本文になる`() {
        val ime = replacingB()
        ime.setComposingText("xy")
        ime.commitText("x")
        ime.setComposingText("y")
        assertEquals("Ax[y]C", "Ax[${ime.content.substring(ime.range.startIndex, ime.range.endIndex)}]C")
        assertEquals("AxC", ime.settled())
    }

    @Test
    fun `C6 空文字で終わったら画面のまま`() {
        val ime = replacingB()
        ime.setComposingText("")
        assertEquals(false, ime.composing)
        assertEquals("AC", ime.settled())
    }

    @Test
    fun `C7 finish で終わったら画面のまま`() {
        val ime = replacingB()
        ime.finishComposingText()
        assertEquals("AxC", ime.settled())
    }

    @Test
    fun `C8 再変換は確定済みの語を元の文字にする`() {
        val ime = Ime("A漢字C", cursor = 3)
        ime.setComposingRegion(1, 3)
        assertEquals("A漢字C", ime.settled())
        ime.setComposingText("かんじ")
        assertEquals("AかんじC", ime.content.toString())
        assertEquals("A漢字C", ime.settled())
    }

    // ------------------------------------------------------------------
    // 7-8: 範囲のずれとアプリの編集
    // ------------------------------------------------------------------

    @Test
    fun `C9 変換を始める前に確定した文字は残す`() {
        val ime = Ime("ABC", cursor = 0)
        ime.commitText("Z")
        ime.select(4, 4)
        ime.setComposingText("て")
        assertEquals("ZABC", ime.settled())
    }

    @Test
    fun `C10 範囲の外の挿入と削除でずれた範囲を使い元の文字を失わない`() {
        val ime = Ime("ABCD")
        ime.select(2, 3)
        ime.setComposingText("x")
        ime.appInsert(0, "Z")
        assertEquals(3, ime.range.startIndex)
        assertEquals(4, ime.range.endIndex)
        assertEquals("ZABCD", ime.settled())
        ime.appDelete(1, 2)
        assertEquals(2, ime.range.startIndex)
        assertEquals("ZBCD", ime.settled())
        ime.appInsert(4, "!")
        assertEquals("ZBCD!", ime.settled())
        ime.setComposingText("xy")
        assertEquals("ZBCD!", ime.settled())
    }

    @Test
    fun `C11 範囲の端に入ったアプリの編集は保存不可`() {
        val end = replacingB()
        end.appInsert(2, "Z")
        assertEquals("A[xZ]C", "A[${end.content.substring(end.range.startIndex, end.range.endIndex)}]C")
        assertNull(end.settled())

        val start = replacingB()
        start.appInsert(1, "Z")
        assertNull(start.settled())
    }

    @Test
    fun `範囲の中のアプリの編集は保存不可`() {
        val ime = replacingB()
        ime.setComposingText("xy")
        ime.appDelete(1, 2)
        assertNull(ime.settled())
    }

    // ------------------------------------------------------------------
    // 9: 領域の規則・失敗・不明・所有者・復旧
    // ------------------------------------------------------------------

    @Test
    fun `前の範囲を全部含む領域は元の文字の左右に確定済みの文字を足す`() {
        val ime = replacingB()
        ime.setComposingRegion(0, 3)
        assertEquals("ABC", ime.settled())
        ime.setComposingText("y")
        assertEquals("y", ime.content.toString())
        assertEquals("ABC", ime.settled())
    }

    @Test
    fun `前の未確定が一部外へ出る重なりは新しく入った確定済みの文字だけを持つ`() {
        val ime = Ime("ABQCD")
        ime.select(2, 3)
        ime.setComposingText("xy")
        ime.setComposingRegion(1, 3)
        // 外へ出た y は確定、Q の置換は済んだもの。範囲 Bx の元は B だけ。
        assertEquals("AByCD", ime.settled())
        ime.setComposingText("び")
        assertEquals("AByCD", ime.settled())
    }

    @Test
    fun `重ならない領域へ移ると前の未確定は確定し新しい領域は元のまま`() {
        val ime = Ime("ABQCD")
        ime.select(2, 3)
        ime.setComposingText("x")
        ime.setComposingRegion(3, 5)
        assertEquals("ABxCD", ime.settled())
        ime.setComposingText("しー")
        assertEquals("ABxCD", ime.settled())
    }

    @Test
    fun `失敗した IME の更新は前の安全な状態を壊さない`() {
        val ime = replacingB()
        assertEquals(false, ime.setComposingText("a\nb"))
        assertEquals("ABC", ime.settled())
        assertEquals(false, ime.setComposingRegion(9, 20))
        assertEquals("ABC", ime.settled())
        ime.setComposingText("y")
        assertEquals("ABC", ime.settled())
    }

    @Test
    fun `変換していない時の失敗は画面のまま`() {
        val ime = Ime("ABC")
        ime.select(1, 2)
        assertEquals(false, ime.setComposingText("a\nb"))
        assertEquals("ABC", ime.settled())
    }

    @Test
    fun `範囲が読めない・preSet の途中・範囲が長さの外なら保存不可`() {
        val ime = replacingB()
        assertNull(ime.state.settledText(ime.content, composing = true, range = null))

        val preSet = ComposingText().apply { preSetComposing = true }
        assertNull(ime.state.settledText(ime.content, composing = true, range = preSet))

        val outside = ComposingText().apply { set(1, 99) }
        assertNull(ime.state.settledText(ime.content, composing = true, range = outside))
    }

    @Test
    fun `範囲が読めないまま候補を更新したら読めるようになっても保存不可のまま`() {
        val ime = replacingB()
        ime.state.beforeComposingText(ime.content, true, null, 2, 2)
        assertNull(ime.settled())
        ime.setComposingText("xy")
        assertNull(ime.settled())
    }

    @Test
    fun `持ち主でない Content には元の文字を使わない`() {
        val ime = replacingB()
        val other = Content("AxC")
        assertNull(ime.state.settledText(other, composing = true, range = ime.range))
    }

    @Test
    fun `安全を失った後は候補を更新しても領域を変えても保存不可のまま`() {
        val ime = replacingB()
        ime.appInsert(2, "Z")
        ime.setComposingText("y")
        // 範囲の中身は候補と一致するが、Z を飲み込んだので元の文字は分からない。
        assertEquals("y", ime.content.substring(ime.range.startIndex, ime.range.endIndex))
        assertNull(ime.settled())
        ime.setComposingRegion(1, 2)
        assertNull(ime.settled())
    }

    @Test
    fun `保存で本文不一致を検出した後は元の範囲本文へ戻して候補を更新しても保存不可のまま`() {
        val ime = replacingB()
        assertEquals("ABC", ime.settled())
        ime.appInsert(2, "Z")
        assertNull(ime.settled())
        ime.appDelete(2, 3)
        ime.setComposingText("y")
        assertNull(ime.settled())
        assertEquals(true, ime.composing)
        ime.finishComposingText()
        assertEquals("AyC", ime.settled())

        ime.select(1, 2)
        ime.setComposingText("z")
        assertEquals("AyC", ime.settled())
    }

    @Test
    fun `保存で範囲取得失敗を検出した後は有効な範囲で候補を更新しても保存不可のまま`() {
        val ime = replacingB()
        assertNull(ime.state.settledText(ime.content, composing = true, range = null))
        assertNull(ime.settled())
        ime.setComposingText("y")
        assertNull(ime.settled())
        ime.finishComposingText()
        assertEquals("AyC", ime.settled())
    }

    @Test
    fun `終わった後は画面へ戻り次の変換はまた元の文字で保存できる`() {
        val ime = replacingB()
        ime.appInsert(2, "Z")
        assertNull(ime.settled())
        ime.finishComposingText()
        assertEquals("AxZC", ime.settled())

        ime.select(1, 2)
        ime.setComposingText("か")
        assertEquals("AxZC", ime.settled())
    }

    @Test
    fun `別の Content で始めた変換は前の元の文字を流用しない`() {
        val ime = replacingB()
        ime.switchTo("PR", cursor = 1)
        assertEquals("PR", ime.settled())
        ime.setComposingText("x")
        assertEquals("PxR", ime.content.toString())
        assertEquals("PR", ime.settled())
    }

    // ------------------------------------------------------------------
    // 旧方式との差
    // ------------------------------------------------------------------

    @Test
    fun `旧方式は範囲を削るだけなので確定済みの文字を失う`() {
        val c2 = replacingB()
        assertEquals("AC" to "ABC", c2.old() to c2.settled())

        val c4 = replacingB().apply {
            setComposingText("xy")
            setComposingRegion(2, 3)
        }
        assertEquals("AC" to "AxC", c4.old() to c4.settled())

        val c8 = Ime("A漢字C", cursor = 3).apply {
            setComposingRegion(1, 3)
            setComposingText("かんじ")
        }
        assertEquals("AC" to "A漢字C", c8.old() to c8.settled())

        val c10 = Ime("ABC", cursor = 2).apply {
            setComposingText("x")
            appInsert(0, "Z")
        }
        assertEquals("ZAxC" to "ZABC", c10.old() to c10.settled())

        val c11 = replacingB().apply { appInsert(2, "Z") }
        assertEquals("AZC" to null, c11.old() to c11.settled())
    }
}
