package dev.kirin.kiwa.nav

/**
 * 位置履歴。ブラウザの戻る/進むと同じ形 ── 「戻る」の途中で新しい場所へ跳ぶと
 * 「進む」側が捨てられる。
 *
 * **Android を1つも知らない。** タブの通し番号と行番号だけで組んであるので、
 * 実機を出さずに全部試験できる（[Spot] の意味は画面の側が持つ）。
 *
 * `back` / `forward` は末尾が「直近」の [ArrayDeque]。中身を外へ見せる口は無く、
 * 試験は [canGoBack] / [canGoForward] と [goBack] / [goForward] の戻り値だけで
 * 内容を確かめる（末尾から順に取り出せば全部を突き合わせられる）。
 */
class PositionHistory(private val limit: Int = 50) {

    /** タブの通し番号と行番号（**1 始まり**。`MainActivity.goToLine` と同じ数え方）。 */
    data class Spot(val tabId: Long, val line: Int)

    private val back = ArrayDeque<Spot>()
    private val forward = ArrayDeque<Spot>()

    /**
     * 飛ぶ**直前**の場所 [from] を積む。[to] は飛び先。
     *
     * **[from] と [to] が同じなら積まない。** 積むと「元へ」を押しても画面が動かず、
     * もう一度押して初めて戻る ── 押せる顔をした死んだボタンになる（実機 G で踏んだ）。
     * [forgetTab] が隣り合う同じ場所を畳むのと同じ規則で、そちらは「タブが消えて
     * 同じ場所が並んだ」場合、こちらは「動かないジャンプ」の場合。
     *
     * **飛び先が後で決まる操作は [to] を省く**（検索の帯 ── 開いた場所を起点として
     * 覚え、一致から一致への移動では積まない）。
     *
     * back の末尾と完全に同じ [Spot] でも積まない（連続する同一行への record で
     * 履歴が埋まるのを防ぐ）。積んだときだけ forward を空にする ── 新しい枝へ
     * 入ったので、それまでの「進む」側は意味を失う。
     */
    fun record(from: Spot, to: Spot? = null) {
        if (from == to) return
        if (back.isNotEmpty() && back.last() == from) return
        back.addLast(from)
        if (back.size > limit) back.removeFirst()
        forward.clear()
    }

    fun canGoBack(): Boolean = back.isNotEmpty()

    fun canGoForward(): Boolean = forward.isNotEmpty()

    /**
     * 戻り先を返す。戻れなければ null。[current] は今居る場所 ── 進むために
     * forward へ積む。[record] と違い、同じ場所でも積む（明示的な移動なので
     * 「連続する同一行を畳む」規則を適用しない）。
     */
    fun goBack(current: Spot): Spot? {
        if (back.isEmpty()) return null
        val spot = back.removeLast()
        forward.addLast(current)
        return spot
    }

    fun goForward(current: Spot): Spot? {
        if (forward.isEmpty()) return null
        val spot = forward.removeLast()
        back.addLast(current)
        return spot
    }

    /**
     * そのタブのエントリを両側から全部捨てる（並びは保つ）。
     *
     * **捨てた後に残りを畳む。** 間に挟まっていたタブが消えると同じ場所が隣り合うことがあり
     * （`t1:10, t2:20, t1:10` から t2 を捨てると `t1:10, t1:10` が残る）、そのままだと
     * 「元へ」を2回押して**同じ場所に2回止まる** ── 押したのに何も起きていないように見える。
     * [record] が「連続する同一の場所を積まない」と決めているのと同じ規則を、ここでも保つ。
     */
    fun forgetTab(tabId: Long) {
        back.removeAll { it.tabId == tabId }
        forward.removeAll { it.tabId == tabId }
        collapseRepeats(back)
        collapseRepeats(forward)
    }

    private fun collapseRepeats(deque: ArrayDeque<Spot>) {
        val kept = ArrayList<Spot>(deque.size)
        for (spot in deque) if (kept.lastOrNull() != spot) kept.add(spot)
        if (kept.size == deque.size) return
        deque.clear()
        deque.addAll(kept)
    }

    fun clear() {
        back.clear()
        forward.clear()
    }
}
