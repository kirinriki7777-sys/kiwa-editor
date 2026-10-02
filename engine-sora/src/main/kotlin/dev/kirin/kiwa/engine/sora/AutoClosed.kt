package dev.kirin.kiwa.engine.sora

/**
 * Sora が自動で入れた閉じ文字の位置（2026-10-02）。**Sora を呼ばない**ので単体で試験できる。
 *
 * ## なぜ要るか
 *
 * Sora 0.24.6 は `(` で `()` を入れるが、その `)` の手前で `)` を打つと**もう1つ入る**
 * （`widget/CodeEditor.java` の `commitText` に「閉じを踏み越す」処理が無い）。
 * `("hello")` と打つ途中で `)` や `"` を打つと `("hello""))` のように閉じが余る。
 *
 * VS Code の既定（`autoClosingOvertype: auto`）と同じく、**自動で入れた閉じだけ**を踏み越す。
 * 自分で打った `)` の手前でもう一度 `)` を打ったら、ふつうに入る。
 *
 * ## 何を覚えるか
 *
 * 閉じ文字の位置（本文の先頭からの文字数）だけ。本文が変わるたびに [onInsert] / [onDelete] で
 * ずらし、閉じそのものが消えたら忘れる。カーソルが別の行へ移ったら [keepOnly] で忘れる
 * （VS Code も括弧の外へ出たら踏み越さなくなる）。
 */
internal class AutoClosed {

    private val positions = ArrayList<Int>()

    val isEmpty: Boolean get() = positions.isEmpty()

    fun add(index: Int) {
        if (index in positions) return
        positions.add(index)
        // 入れ子で開き続けても数個。上限は、覚え違いが溜まり続けないための保険。
        while (positions.size > MAX) positions.removeAt(0)
    }

    /** [index] に自動の閉じがあれば、それを使い切って true。 */
    fun consume(index: Int): Boolean = positions.remove(index)

    /** [start] に [length] 文字入った。**同じ位置への挿入は閉じの手前に入る**ので後ろへずらす。 */
    fun onInsert(start: Int, length: Int) {
        for (i in positions.indices) {
            if (positions[i] >= start) positions[i] += length
        }
    }

    /** `[start, end)` が消えた。中にあった閉じは忘れる。 */
    fun onDelete(start: Int, end: Int) {
        val length = end - start
        positions.removeAll { it in start until end }
        for (i in positions.indices) {
            if (positions[i] >= end) positions[i] -= length
        }
    }

    /** [keep] が false を返す位置を忘れる（カーソルの行に無いものを捨てるのに使う）。 */
    fun keepOnly(keep: (Int) -> Boolean) {
        positions.retainAll(keep)
    }

    fun clear() {
        positions.clear()
    }

    private companion object {
        const val MAX = 32
    }
}
