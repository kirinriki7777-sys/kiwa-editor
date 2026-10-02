package dev.kirin.kiwa.engine.sora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空の括弧を Backspace で組ごと消す条件。
 *
 * **消しすぎると打ったものが消える**ので、組でない形は必ず1文字だけにする。
 */
class BracketPairsTest {

    /** kotlin の `language-configuration.json` が自動で閉じる括弧と同じ。 */
    private val kotlin: (Char) -> Collection<String> = { open ->
        when (open) {
            '(' -> listOf(")")
            '[' -> listOf("]")
            '{' -> listOf("}")
            else -> emptyList()
        }
    }

    /** `.c` や `.txt` のように、括弧を自動で閉じない言語。 */
    private val none: (Char) -> Collection<String> = { emptyList() }

    @Test
    fun `空の組の間なら3種類とも一緒に消す`() {
        assertTrue(BracketPairs.deletesBoth("()", 1, kotlin))
        assertTrue(BracketPairs.deletesBoth("[]", 1, kotlin))
        assertTrue(BracketPairs.deletesBoth("{}", 1, kotlin))
    }

    @Test
    fun `B3 の続き foo(|)a) から消すと fooa) に戻る形になる`() {
        // `    foo(` の直後 = 0 始まりの桁 8（状態表示では桁 9）。
        // `(` と `)` を一緒に消せば、打ち直す前の `fooa)` に戻る
        assertTrue(BracketPairs.deletesBoth("    foo()a)", 8, kotlin))
    }

    @Test
    fun `中身がある括弧は開きだけを消す`() {
        // 中身のある括弧で両方消すと、`a` の後ろの `)` まで失う
        assertFalse(BracketPairs.deletesBoth("    foo(a)", 8, kotlin))
    }

    @Test
    fun `組が食い違っていれば消さない`() {
        assertFalse(BracketPairs.deletesBoth("(]", 1, kotlin))
        assertFalse(BracketPairs.deletesBoth("{)", 1, kotlin))
    }

    @Test
    fun `閉じの後ろにいるときは組と見なさない`() {
        assertFalse(BracketPairs.deletesBoth("()", 2, kotlin))
    }

    @Test
    fun `行の端では何もしない`() {
        assertFalse(BracketPairs.deletesBoth("", 0, kotlin))
        assertFalse(BracketPairs.deletesBoth("(", 1, kotlin))
        assertFalse(BracketPairs.deletesBoth(")", 0, kotlin))
    }

    @Test
    fun `括弧を自動で閉じない言語では1文字ずつ消す`() {
        assertFalse(BracketPairs.deletesBoth("()", 1, none))
    }

    @Test
    fun `引用符は対象外`() {
        val quotes: (Char) -> Collection<String> = { open -> listOf(open.toString()) }
        assertFalse(BracketPairs.deletesBoth("\"\"", 1, quotes))
        assertFalse(BracketPairs.deletesBoth("''", 1, quotes))
    }

    // ------------------------------------------------------------------
    // 閉じ括弧の行を揃える
    // ------------------------------------------------------------------

    private fun indentFor(lines: List<String>, line: Int, column: Int, close: Char): String? =
        BracketPairs.closingIndent({ lines[it] }, line, column, close)

    /** try-with-resources 内で改行して `}` を打つ例。 */
    private val mainJava = listOf(
        "public class Main{",
        "    public static void main(String[] args){",
        "        try(FileWriter fw=new FileWriter(\"data.txt\");){",
        "            fw.write(\"hello\");",
        "            ",
        "        }",
        "    }",
        "}"
    )

    @Test
    fun `Main java の 7 行目は try の行と同じ深さへ戻る`() {
        assertEquals("        ", indentFor(mainJava, 4, 12, '}'))
    }

    @Test
    fun `間に閉じた組があれば飛ばして外側の開きに揃える`() {
        val lines = listOf(
            "fun f() {",
            "    if (a) {",
            "        b()",
            "    }",
            "        "
        )
        assertEquals("", indentFor(lines, 4, 8, '}'))
    }

    @Test
    fun `丸括弧と角括弧も同じ種類どうしで数える`() {
        val lines = listOf("    call(", "        a[0],", "        ")
        assertEquals("    ", indentFor(lines, 2, 8, ')'))
        val list = listOf("  val x = [", "      1,", "      ")
        assertEquals("  ", indentFor(list, 2, 6, ']'))
    }

    @Test
    fun `カーソルより前に文字があれば揃えない`() {
        val lines = listOf("if (a) {", "    b }")
        assertNull(indentFor(lines, 1, 6, '}'))
    }

    @Test
    fun `タブの字下げはタブのまま写す`() {
        val lines = listOf("\tif (a) {", "\t\tb", "\t\t")
        assertEquals("\t", indentFor(lines, 2, 2, '}'))
    }

    @Test
    fun `対応する開きが無ければ揃えない`() {
        assertNull(indentFor(listOf("a", "    "), 1, 4, '}'))
        // 閉じだけ多い ── 打ち間違いの途中。勝手に動かさない
        assertNull(indentFor(listOf("{", "}", "    "), 2, 4, '}'))
    }

    @Test
    fun `括弧でない文字では揃えない`() {
        assertNull(indentFor(listOf("{", "    "), 1, 4, 'a'))
        assertNull(indentFor(listOf("\"", "    "), 1, 4, '"'))
    }

    @Test
    fun `上限より上は数えない`() {
        val lines = listOf("{") + List(10) { "    x" } + listOf("    ")
        assertNull(BracketPairs.closingIndent({ lines[it] }, 11, 4, '}', limit = 5))
        assertEquals("", BracketPairs.closingIndent({ lines[it] }, 11, 4, '}', limit = 11))
    }
}
