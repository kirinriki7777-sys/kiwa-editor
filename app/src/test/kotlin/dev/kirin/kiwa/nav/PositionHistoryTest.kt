package dev.kirin.kiwa.nav

import dev.kirin.kiwa.nav.PositionHistory.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PositionHistory] の入出力表（発注文の表1・13行）を機械で踏む。
 *
 * [PositionHistory] は中身を外へ見せる口を持たないので、各試験は record/goBack/goForward
 * だけで表の「前の」状態を組み立て、操作した後の内容を [drainBack] / [drainForward] で
 * 末尾から取り出して突き合わせる（取り出しは末尾＝直近から先に出る）。
 */
class PositionHistoryTest {

    // goBack/goForward は覗いた側の反対側へ引数の Spot を積んでしまう（正しい挙動）ので、
    // 両側の中身を続けて覗くと片方の drain がもう片方を汚す。実データに出ない値にして、
    // 出てきたら黙って濾すことでその汚染を無視する。
    private val dummy = Spot(Long.MIN_VALUE, Int.MIN_VALUE)

    private fun drainBack(h: PositionHistory): List<Spot> {
        val out = ArrayList<Spot>()
        while (h.canGoBack()) out.add(h.goBack(dummy)!!)
        return out.filterNot { it == dummy }
    }

    private fun drainForward(h: PositionHistory): List<Spot> {
        val out = ArrayList<Spot>()
        while (h.canGoForward()) out.add(h.goForward(dummy)!!)
        return out.filterNot { it == dummy }
    }

    @Test
    fun `1 最初の record は back に積む`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        assertTrue(h.canGoBack())
        assertFalse(h.canGoForward())
        assertEquals(listOf(Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `2 back の末尾と同じ record は積まない`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(1, 10))
        assertTrue(h.canGoBack())
        assertFalse(h.canGoForward())
        assertEquals(listOf(Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `3 違う行への record は積み増す`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(1, 20))
        assertTrue(h.canGoBack())
        assertFalse(h.canGoForward())
        assertEquals(listOf(Spot(1, 20), Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `4 違うタブへの record は積み増す`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(2, 10))
        assertTrue(h.canGoBack())
        assertFalse(h.canGoForward())
        assertEquals(listOf(Spot(2, 10), Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `5 record は forward を空にする`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(1, 20))
        h.record(Spot(1, 30))
        assertEquals(Spot(1, 30), h.goBack(Spot(1, 30))) // 前の状態を作る: back=[10,20] fwd=[30]
        h.record(Spot(1, 40))
        assertTrue(h.canGoBack())
        assertFalse(h.canGoForward())
        assertEquals(listOf(Spot(1, 40), Spot(1, 20), Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `6 空の履歴で goBack は null`() {
        val h = PositionHistory()
        assertNull(h.goBack(Spot(1, 50)))
        assertFalse(h.canGoBack())
        assertFalse(h.canGoForward())
    }

    @Test
    fun `7 goBack は末尾を返し forward へ積む`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        assertEquals(Spot(1, 10), h.goBack(Spot(1, 50)))
        assertFalse(h.canGoBack())
        assertTrue(h.canGoForward())
        assertEquals(listOf(Spot(1, 50)), drainForward(h))
    }

    @Test
    fun `8 goBack は back に1つ残せる`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(1, 20))
        assertEquals(Spot(1, 20), h.goBack(Spot(1, 50)))
        assertTrue(h.canGoBack())
        assertTrue(h.canGoForward())
        assertEquals(listOf(Spot(1, 50)), drainForward(h))
        assertEquals(listOf(Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `9 goForward は末尾を返し back へ積む`() {
        val h = PositionHistory()
        h.record(Spot(1, 30))
        assertEquals(Spot(1, 30), h.goBack(Spot(1, 30))) // 前の状態を作る: back=[] fwd=[30]
        assertEquals(Spot(1, 30), h.goForward(Spot(1, 50)))
        assertFalse(h.canGoForward())
        assertTrue(h.canGoBack())
        assertEquals(listOf(Spot(1, 50)), drainBack(h))
    }

    @Test
    fun `10 空の履歴で goForward は null`() {
        val h = PositionHistory()
        assertNull(h.goForward(Spot(1, 50)))
        assertFalse(h.canGoBack())
        assertFalse(h.canGoForward())
    }

    @Test
    fun `11 forgetTab は両側から並びを保って捨てる`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(2, 20))
        h.record(Spot(1, 30))
        h.record(Spot(2, 40))
        assertEquals(Spot(2, 40), h.goBack(Spot(2, 40))) // 前の状態を作る: back=[t1:10,t2:20,t1:30] fwd=[t2:40]
        h.forgetTab(2)
        assertTrue(h.canGoBack())
        assertFalse(h.canGoForward())
        assertEquals(listOf(Spot(1, 30), Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `12 limit を超えたら古い方から捨てる`() {
        val h = PositionHistory(limit = 3)
        h.record(Spot(1, 1))
        h.record(Spot(1, 2))
        h.record(Spot(1, 3))
        h.record(Spot(1, 4))
        assertTrue(h.canGoBack())
        assertEquals(listOf(Spot(1, 4), Spot(1, 3), Spot(1, 2)), drainBack(h))
    }

    @Test
    fun `13 clear は両側とも空にする`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(1, 30))
        h.goBack(Spot(1, 30)) // 前の状態を作る: back=[t1:10] fwd=[t1:30]
        h.clear()
        assertFalse(h.canGoBack())
        assertFalse(h.canGoForward())
    }

    // 14 は受入で足した行（発注文の表1には無かった）。間に挟まったタブを捨てると
    // 同じ場所が隣り合い、「元へ」を2回押して同じ所に2回止まる ──
    // 押したのに何も起きていないように見えるので、record と同じ規則で畳む。
    @Test
    fun `14 forgetTab で隣り合った同じ場所は畳む`() {
        val h = PositionHistory()
        h.record(Spot(1, 10))
        h.record(Spot(2, 20))
        h.record(Spot(1, 10))
        h.forgetTab(2)
        assertEquals(listOf(Spot(1, 10)), drainBack(h))
    }

    // 15〜17 は実機 G で踏んだ欠陥から足した行（発注文の表1にも受入 P8 の読みにも無かった）。
    // 「同じ場所は積まない」は back の末尾との比較だけでは足りず、**飛んでも動かない**
    // ジャンプそのものを弾く必要がある ── 積むと「元へ」が1回空振りする。

    @Test
    fun `15 飛び先が今の場所と同じなら積まない`() {
        val h = PositionHistory()
        h.record(Spot(1, 10), Spot(1, 300))
        h.record(Spot(1, 300), Spot(1, 300))
        // 300 行目から 300 行目への「移動」は履歴に足さない ── 足すと goBack が
        // 同じ場所を返し、画面が動かないまま「元へ」が1回消費される。
        assertEquals(listOf(Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `16 飛び先が違えば積む`() {
        val h = PositionHistory()
        h.record(Spot(1, 10), Spot(1, 300))
        h.record(Spot(1, 300), Spot(1, 100))
        assertEquals(listOf(Spot(1, 300), Spot(1, 10)), drainBack(h))
    }

    @Test
    fun `17 同じ行でもタブが違えば積む`() {
        val h = PositionHistory()
        // タブをまたぐ移動は、行番号が同じでも別の場所。
        h.record(Spot(1, 10), Spot(2, 10))
        assertEquals(listOf(Spot(1, 10)), drainBack(h))
    }
}
