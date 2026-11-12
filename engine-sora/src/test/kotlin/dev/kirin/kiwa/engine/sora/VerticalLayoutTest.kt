package dev.kirin.kiwa.engine.sora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 縦書きレイアウト・座標・文字方向・ヒットテストの単体試験。
 *
 * 描画結果は実機でしか見えないが、文字の向き、行（列）分割、
 * 座標と文字位置の相互変換、矢印キーの読み替えは数学的・論理的にJVM上で検証できる。
 */
class VerticalLayoutTest {

    // ICU・フォントの代わり。実際の Unicode 表を固定する試験にはしない。
    private fun build(
        text: CharSequence,
        lineHeight: Float,
        columnWidth: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        paddingTop: Float = 16f,
        paddingBottom: Float = 16f,
        paddingLeft: Float = 16f,
        paddingRight: Float = 16f,
        orientation: (Int) -> VerticalOrientation = { if (it < 128) VerticalOrientation.R else VerticalOrientation.U },
        hasVerticalGlyph: (String) -> Boolean = { false },
        lineBreaks: (String) -> Set<Int> = { (0..it.length).toSet() },
        sidewaysAdvance: (String) -> Float = { lineHeight * 0.55f }
    ) = VerticalLayout.build(
        verticalParagraphsOf(text), lineHeight, columnWidth, viewportWidth, viewportHeight,
        paddingTop, paddingBottom, paddingLeft, paddingRight,
        orientation, hasVerticalGlyph, lineBreaks, sidewaysAdvance
    )

    @Test
    fun `R1_UとTuは正立でRは横倒し_区分はクラスタの先頭で決まる`() {
        val layout = build(
            "漢、a😀※か\u3099", 20f, 30f, 300f, 300f,
            orientation = {
                when (it) {
                    '、'.code -> VerticalOrientation.TU
                    'a'.code -> VerticalOrientation.R
                    else -> VerticalOrientation.U
                }
            },
            hasVerticalGlyph = { error("Tr 以外には問い合わせない") }
        )
        assertEquals(listOf(true, true, false, true, true, true), layout.columns.single().glyphs.map { it.isUpright })
        assertEquals(listOf("漢", "、", "a", "😀", "※", "か\u3099"), layout.columns.single().glyphs.map { it.text })
    }

    @Test
    fun `R1_Trは縦用字形があれば正立で無ければ横倒し`() {
        val asked = mutableListOf<String>()
        val layout = build(
            "「ー；", 20f, 30f, 300f, 200f,
            orientation = { VerticalOrientation.TR },
            hasVerticalGlyph = { asked.add(it); it != "；" }
        )
        assertEquals(listOf("「", "ー", "；"), asked)
        assertEquals(listOf(true, true, false), layout.columns.single().glyphs.map { it.isUpright })
    }

    @Test
    fun `サロゲートペアの漢字を分割せず正立で配置して移動できる`() {
        val layout = build("𠮷野家", 20f, 30f, 300f, 200f)
        val first = layout.columns[0].glyphs.first()
        assertEquals("𠮷", first.text)
        assertEquals(2, first.length)
        assertTrue("サロゲートペア漢字も正立", first.isUpright)
        assertEquals(2, layout.nextOffset(0, ArrowDirection.DOWN))
        assertEquals(0, layout.nextOffset(2, ArrowDirection.UP))
    }

    // ------------------------------------------------------------------
    // レイアウトと列分割
    // ------------------------------------------------------------------

    @Test
    fun `改行によって列が分割され右から左へ並ぶ`() {
        val text = "第一段落\n第二段落"
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f,
            paddingTop = 10f,
            paddingBottom = 10f,
            paddingLeft = 10f,
            paddingRight = 10f
        )

        assertEquals("2段落で2列", 2, layout.columns.size)

        val col0 = layout.columns[0]
        val col1 = layout.columns[1]

        // 列0（先頭）は右端に配置
        assertEquals(0, col0.startOffset)
        assertEquals(4, col0.glyphs.size)
        // 列1は列0の左側に配置（RTL）
        assertTrue("列1は列0より左", col1.centerX < col0.centerX)
        assertEquals(col0.centerX - 30f, col1.centerX, 0.001f)
    }

    @Test
    fun `高さ制限を超えると自動的に次の列へ折り返される`() {
        // 使用可能高さ = 200 - 10 - 10 = 180f。1文字 20f なので 9文字で1列
        val text = "あいうえおかきくけこさしすせそ" // 15文字
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f,
            paddingTop = 10f,
            paddingBottom = 10f,
            paddingLeft = 10f,
            paddingRight = 10f
        )

        assertEquals("折り返されて2列", 2, layout.columns.size)
        assertEquals("1列目は9文字", 9, layout.columns[0].glyphs.size)
        assertEquals("2列目は6文字", 6, layout.columns[1].glyphs.size)
    }

    // ------------------------------------------------------------------
    // ヒットテスト（座標 → オフセット）
    // ------------------------------------------------------------------

    @Test
    fun `タップ位置から対応する文字オフセットが引ける`() {
        val text = "あいうえお\nかきくけこ"
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f,
            paddingTop = 10f,
            paddingBottom = 10f,
            paddingRight = 10f
        )

        val col0 = layout.columns[0]
        // 列0（あいうえお）の先頭付近をタップ
        val offset0 = layout.offsetAt(col0.centerX, 15f)
        assertEquals(0, offset0)

        // 列0の2文字目（い）付近をタップ（Y = 10 + 20 + 10 = 40f）
        val offset1 = layout.offsetAt(col0.centerX, 40f)
        assertEquals(1, offset1)

        // 列1（かきくけこ）の先頭付近をタップ
        val col1 = layout.columns[1]
        val offsetCol1 = layout.offsetAt(col1.centerX, 15f)
        assertEquals(6, offsetCol1) // "あいうえお\n" の次なので 6
    }

    // ------------------------------------------------------------------
    // カーソル位置の幾何
    // ------------------------------------------------------------------

    @Test
    fun `カーソル位置は列の中心を基準に対称な水平バーとなる`() {
        val text = "あいう"
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f,
            paddingTop = 10f,
            paddingRight = 10f
        )

        val cursor0 = layout.cursorPosition(0)
        assertEquals(10f, cursor0.y, 0.001f)
        // x1 と x2 は centerX に対称
        assertEquals(cursor0.centerX, (cursor0.x1 + cursor0.x2) / 2f, 0.001f)

        // 末尾カーソル
        val cursorEnd = layout.cursorPosition(3)
        assertEquals(10f + 3 * 20f, cursorEnd.y, 0.001f)
    }

    // ------------------------------------------------------------------
    // 矢印キー移動（確定時）
    // ------------------------------------------------------------------

    @Test
    fun `矢印キー移動_DOWNで下へ進みUPで上へ戻る`() {
        val text = "あいうえお"
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f
        )

        assertEquals(1, layout.nextOffset(0, ArrowDirection.DOWN))
        assertEquals(2, layout.nextOffset(1, ArrowDirection.DOWN))
        assertEquals(1, layout.nextOffset(2, ArrowDirection.UP))
        assertEquals(0, layout.nextOffset(1, ArrowDirection.UP))
        assertEquals(0, layout.nextOffset(0, ArrowDirection.UP)) // 先頭で止まる
    }

    @Test
    fun `矢印キー移動_LEFTで左の列へ進みRIGHTで右の列へ戻る`() {
        val text = "あいう\nかきく"
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f
        )

        // 列0の「い」（offset 1）から LEFT を押すと、列1（左の列）の同等の高さにある「き」（offset 5）へ
        val movedLeft = layout.nextOffset(1, ArrowDirection.LEFT)
        assertEquals(5, movedLeft)

        // 列1から RIGHT を押すと列0へ戻る
        val movedRight = layout.nextOffset(movedLeft, ArrowDirection.RIGHT)
        assertEquals(1, movedRight)
    }

    @Test
    fun `矢印キー移動_列の末尾からは隣の列の近い高さへ移る`() {
        // 列の末尾（最後の文字の後ろ）にいる時、隣の列の先頭へ跳ばない
        val same = build("あいう\nかきく", 20f, 30f, 300f, 200f)
        assertEquals("列0の末尾から LEFT で列1の末尾へ", 7, same.nextOffset(3, ArrowDirection.LEFT))
        assertEquals("列1の末尾から RIGHT で列0の末尾へ", 3, same.nextOffset(7, ArrowDirection.RIGHT))

        // 隣の列が短い時は、その列の末尾がいちばん近い
        val shorter = build("あいうえお\nか", 20f, 30f, 300f, 200f)
        assertEquals("「お」から LEFT で短い列の末尾へ", 7, shorter.nextOffset(4, ArrowDirection.LEFT))
    }

    @Test
    fun `矢印キー移動_折り返しで終わる列の末尾へは移らない`() {
        // 1列 9 文字で折り返す 18 文字。列0の末尾（offset 9）は列1の先頭として出るので、
        // そこを選ぶと RIGHT を押しても列を移れない。
        val layout = build(
            text = "あ".repeat(18),
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f,
            paddingTop = 10f,
            paddingBottom = 10f
        )
        assertEquals(2, layout.columns.size)
        assertEquals("文末から RIGHT で列0の最後の文字の手前へ", 8, layout.nextOffset(18, ArrowDirection.RIGHT))
    }

    // ------------------------------------------------------------------
    // 必須補正 回帰試験（Sol 反例 5 件）
    // ------------------------------------------------------------------

    @Test
    fun `反例1_RTLスクロール_末尾到達とタップ可能`() {
        val text = "あ".repeat(20)
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 100f,
            viewportHeight = 60f,
            paddingTop = 10f,
            paddingBottom = 10f,
            paddingLeft = 10f,
            paddingRight = 10f
        )

        val x0 = layout.cursorPosition(20, 0f).centerX
        val xMax = layout.cursorPosition(20, layout.maxScrollX).centerX
        // scroll=0 では末尾（左端列）は画面左外、scroll=maxScrollX では画面内（0..100）に入る
        assertTrue("scroll=0で末尾は左外: x0=$x0", x0 < 0f)
        assertTrue("scroll=maxScrollXで末尾は画面内: xMax=$xMax", xMax in 0f..100f)

        // maxScrollX 時に末尾列のX座標（xMax）の字下半分をタップすると末尾オフセット20が引ける
        val tappedOffset = layout.offsetAt(xMax, 45f, layout.maxScrollX)
        assertEquals("末尾位置をタップしてオフセット20に到達", 20, tappedOffset)
    }

    @Test
    fun `反例2_無言切詰の防止_10001文字でも全文字がレイアウトに保持される`() {
        val text = "あ".repeat(10001)
        val layout = build(
            text = text,
            lineHeight = 20f,
            columnWidth = 30f,
            viewportWidth = 300f,
            viewportHeight = 200f
        )
        val drawn = layout.columns.sumOf { it.glyphs.size }
        assertEquals("10001文字すべてがグリフとして保持される（無言切詰なし）", 10001, drawn)
    }

    @Test
    fun `反例3_末尾改行と空行の列`() {
        // 空文書: 1列（空）
        val emptyDoc = build("", 20f, 30f, 300f, 200f, paddingTop = 10f)
        assertEquals("空文書は1列", 1, emptyDoc.columns.size)
        assertEquals(0, emptyDoc.columns[0].glyphs.size)
        assertEquals(10, emptyDoc.cursorPosition(0).lineTop.toInt())

        // 末尾改行: 2列（2列目は空）
        val trailingNl = build("あ\n", 20f, 30f, 300f, 200f)
        assertEquals("あ\\n は2列", 2, trailingNl.columns.size)
        assertEquals(1, trailingNl.columns[0].glyphs.size)
        assertEquals(0, trailingNl.columns[1].glyphs.size)
        assertEquals(2, trailingNl.columns[1].startOffset)

        // 連続改行
        val doubleNl = build("あ\n\n", 20f, 30f, 300f, 200f)
        assertEquals("あ\\n\\n は3列", 3, doubleNl.columns.size)
        assertEquals(0, doubleNl.columns[1].glyphs.size)
        assertEquals(0, doubleNl.columns[2].glyphs.size)

        // 改行のみ
        val onlyNl = build("\n", 20f, 30f, 300f, 200f)
        assertEquals("\\n は2列", 2, onlyNl.columns.size)

        // 列間移動（LEFT/RIGHT）
        val movedLeft = trailingNl.nextOffset(0, ArrowDirection.LEFT)
        assertEquals("Col0からLEFTでCol1の末尾オフセット2へ", 2, movedLeft)
        val movedRight = trailingNl.nextOffset(2, ArrowDirection.RIGHT)
        assertEquals("Col1からRIGHTでCol0のオフセット0へ", 0, movedRight)
    }

    @Test
    fun `反例4_CRとCRLF改行の列分割とカーソル`() {
        // CR 改行
        val crDoc = build("あ\rい", 20f, 30f, 300f, 200f)
        assertEquals("CRで2列に分割", 2, crDoc.columns.size)
        assertEquals(1, crDoc.columns[0].glyphs.size)
        assertEquals(1, crDoc.columns[1].glyphs.size)

        // CRLF 改行
        val crlfDoc = build("あ\r\nい", 20f, 30f, 300f, 200f)
        assertEquals("CRLFで2列に分割", 2, crlfDoc.columns.size)
        assertEquals(1, crlfDoc.columns[0].glyphs.size)
        assertEquals(1, crlfDoc.columns[1].glyphs.size)
        assertEquals("Col1はoffset3から開始", 3, crlfDoc.columns[1].startOffset)

        // CRLF 途中にカーソルを置かない
        val curMid = crlfDoc.cursorPosition(2) // offset 2 は \r と \n の中間
        val curBefore = crlfDoc.cursorPosition(1) // \r の直前
        assertEquals("CRLF中間のカーソルは手前に丸められる", curBefore.y, curMid.y, 0.001f)

        // 矢印キー移動でCRLF中間へ入らない
        val nextFrom1 = crlfDoc.nextOffset(1, ArrowDirection.DOWN)
        assertEquals("offset1からDOWNでCRLFを跨いでoffset3へ", 3, nextFrom1)
        val prevFrom3 = crlfDoc.nextOffset(3, ArrowDirection.UP)
        assertEquals("offset3からUPでCRLFを跨いでoffset1へ", 1, prevFrom3)
    }

    @Test
    fun `反例5_結合文字_結合濁点と異体字セレクタとZWJ絵文字のクラスタ境界`() {
        // 結合濁点
        val combined = build("か\u3099", 20f, 30f, 300f, 200f)
        assertEquals("か+結合濁点は1グリフ", 1, combined.columns[0].glyphs.size)
        assertEquals("DOWNでクラスタ境界2へ進む", 2, combined.nextOffset(0, ArrowDirection.DOWN))
        assertEquals("UPでクラスタ境界0へ戻る", 0, combined.nextOffset(2, ArrowDirection.UP))

        // 異体字セレクタ (葛\uFE00)
        val ivs = build("葛\uFE00", 20f, 30f, 300f, 200f)
        assertEquals("葛+VSは1グリフ", 1, ivs.columns[0].glyphs.size)
        assertEquals("DOWNでクラスタ境界2へ進む", 2, ivs.nextOffset(0, ArrowDirection.DOWN))

        // ZWJ絵文字 (家族: 👨‍👩‍👧‍👦 = 11 UTF-16 code units)
        val zwj = build("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66", 20f, 30f, 300f, 200f)
        assertEquals("ZWJ絵文字は1グリフ", 1, zwj.columns[0].glyphs.size)
        assertEquals("DOWNでクラスタ境界11へ進む", 11, zwj.nextOffset(0, ArrowDirection.DOWN))

        // ヒットテストもクラスタ境界
        val hitOffset = combined.offsetAt(combined.columns[0].centerX, 15f)
        assertTrue("ヒットテストがクラスタ途中（1）にならない", hitOffset == 0 || hitOffset == 2)
    }

    @Test
    fun `R2_独立した1桁と2桁の数字は縦中横の1マス`() {
        for ((text, expected) in listOf("第3章" to "3", "12月" to "12", "12" to "12")) {
            val layout = build(text, 20f, 30f, 300f, 300f)
            val tcy = layout.columns.single().glyphs.single { it.isTateChuYoko }
            assertEquals(expected, tcy.text)
            assertEquals(expected.length, tcy.length)
            assertEquals(20f, tcy.bottomY - tcy.topY, 0.001f)
            assertEquals(text, layout.columns.flatMap { it.glyphs }.joinToString("") { it.text })
        }
    }

    @Test
    fun `R2_3桁以上やASCII記号と英字に接する数字は横倒し`() {
        for (text in listOf("2026年", "3.14", "12:30", "50%", "A4", "v2", "1,000", "!12", "12~")) {
            val glyphs = build(text, 20f, 30f, 300f, 300f).columns.single().glyphs
            assertFalse(text, glyphs.any { it.isTateChuYoko })
            assertTrue(text, glyphs.filter { it.text[0] in '0'..'9' }.all { !it.isUpright && it.length == 1 })
        }
    }

    @Test
    fun `R2_全角数字は1字ずつ正立で改行の両側は段落端になる`() {
        val glyphs = build("１２\r\n12\n3", 20f, 30f, 300f, 300f).columns.map { it.glyphs }
        assertEquals(listOf("１", "２"), glyphs[0].map { it.text })
        assertTrue(glyphs[0].all { it.isUpright && !it.isTateChuYoko })
        assertEquals("12", glyphs[1].single().text)
        assertTrue(glyphs[1].single().isTateChuYoko)
        assertTrue(glyphs[2].single().isTateChuYoko)
    }

    @Test
    fun `R3_行頭禁則の句点は前の字ごと次の列へ送る`() {
        val layout = build("あいう。え", 20f, 30f, 300f, 60f,
            paddingTop = 0f, paddingBottom = 0f,
            lineBreaks = { setOf(0, 1, 2, 4, 5) })
        assertEquals(listOf("あい", "う。え"), layout.columns.map { it.glyphs.joinToString("") { g -> g.text } })
        assertEquals(2, layout.columns[0].endOffset)
        assertEquals(2, layout.columns[1].startOffset)
        assertEquals(0f, layout.columns[1].glyphs.first().topY, 0.001f)
        assertEquals(layout.columns[1].centerX, layout.columns[1].glyphs.first().centerX, 0.001f)
    }

    @Test
    fun `R3_行末禁則の開き括弧は列の末に残さない`() {
        val layout = build("あい「うえ", 20f, 30f, 300f, 60f,
            paddingTop = 0f, paddingBottom = 0f,
            lineBreaks = { setOf(0, 1, 2, 4, 5) })
        assertEquals(listOf("あい", "「うえ"), layout.columns.map { it.glyphs.joinToString("") { g -> g.text } })
    }

    @Test
    fun `R3_分割禁止のリーダ2字は同じ列へ送る`() {
        val layout = build("あい……う", 20f, 30f, 300f, 60f,
            paddingTop = 0f, paddingBottom = 0f,
            orientation = { if (it == '…'.code) VerticalOrientation.R else VerticalOrientation.U },
            lineBreaks = { setOf(0, 1, 2, 4, 5) }, sidewaysAdvance = { 20f })
        assertEquals(listOf("あい", "……う"), layout.columns.map { it.glyphs.joinToString("") { g -> g.text } })
    }

    @Test
    fun `R3_長い不可分語は収まらないマスの先頭で折る`() {
        val layout = build("abcdef", 20f, 30f, 300f, 40f,
            paddingTop = 0f, paddingBottom = 0f,
            lineBreaks = { setOf(0, it.length) }, sidewaysAdvance = { 20f })
        assertEquals(listOf("ab", "cd", "ef"), layout.columns.map { it.glyphs.joinToString("") { g -> g.text } })
    }

    @Test
    fun `R3_縦中横の途中の折り位置は使わず1マスのまま送る`() {
        val layout = build("あ12い", 20f, 30f, 300f, 20f,
            paddingTop = 0f, paddingBottom = 0f,
            lineBreaks = { setOf(0, 2, 4) })
        assertEquals(listOf("あ", "12", "い"), layout.columns.map { it.glyphs.single().text })
        assertEquals(2, layout.columns[1].glyphs.single().length)
    }

    @Test
    fun `R3_折り位置は改行を含まない段落ごとの相対位置`() {
        val paragraphs = mutableListOf<String>()
        val layout = build("あい\r\nうえ。お\n\n", 20f, 30f, 300f, 40f,
            paddingTop = 0f, paddingBottom = 0f,
            lineBreaks = { paragraphs.add(it); if (it == "うえ。お") setOf(0, 1, 3, 4) else (0..it.length).toSet() })
        assertEquals(listOf("あい", "うえ。お", "", ""), paragraphs)
        assertEquals(listOf("あい", "う", "え。", "お", "", ""),
            layout.columns.map { it.glyphs.joinToString("") { g -> g.text } })
        assertEquals(5, layout.columns[1].endOffset)
        assertEquals(5, layout.columns[2].startOffset)
    }

    @Test
    fun `R4_空白とタブは固定の空きで送り0だけ4分の1マスにする`() {
        val measured = mutableListOf<String>()
        val glyphs = build(" 　\tA", 20f, 30f, 300f, 300f,
            sidewaysAdvance = { measured.add(it); 0f }).columns.single().glyphs
        assertEquals(listOf(10f, 20f, 40f, 5f), glyphs.map { it.bottomY - it.topY })
        assertEquals(listOf(true, true, true, false), glyphs.map { it.isWhitespace })
        assertEquals(listOf("A"), measured)
    }

    @Test
    fun `R4_横倒しはクラスタ全体を測り小さな送りに下限を付けない`() {
        val measured = mutableListOf<String>()
        val glyph = build("a\u0301", 20f, 30f, 300f, 300f,
            sidewaysAdvance = { measured.add(it); 1f }).columns.single().glyphs.single()
        assertEquals(listOf("a\u0301"), measured)
        assertEquals(1f, glyph.bottomY - glyph.topY, 0.001f)
    }

    @Test
    fun `R6_縦中横のタップは両端で途中カーソルは中央になり上下は1マスを越える`() {
        val layout = build("第12章", 20f, 30f, 300f, 300f)
        val glyph = layout.columns.single().glyphs[1]
        for (y in glyph.topY.toInt()..glyph.bottomY.toInt()) {
            assertTrue(layout.offsetAt(glyph.centerX, y.toFloat()) in listOf(1, 3))
        }
        assertEquals(1, layout.offsetAt(glyph.centerX, glyph.topY + 1))
        assertEquals(3, layout.offsetAt(glyph.centerX, glyph.bottomY - 1))
        assertEquals(glyph.topY, layout.cursorPosition(1).y, 0.001f)
        assertEquals(glyph.centerY, layout.cursorPosition(2).y, 0.001f)
        assertEquals(glyph.bottomY, layout.cursorPosition(3).y, 0.001f)
        assertEquals(3, layout.nextOffset(1, ArrowDirection.DOWN))
        assertEquals(1, layout.nextOffset(3, ArrowDirection.UP))
        assertEquals(1, layout.nextOffset(2, ArrowDirection.UP))
        assertEquals(3, layout.nextOffset(2, ArrowDirection.DOWN))
    }
}
