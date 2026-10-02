package dev.kirin.kiwa.search

import dev.kirin.editoradapter.SearchQuery
import dev.kirin.kiwa.file.TextFile
import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * フォルダを丸ごと検索する。
 *
 * **Android を1つも知らない。** `File` と [SearchQuery]（検索欄と同じ条件の型）
 * だけで組んであるので、実機を出さずに全部試験できる。
 *
 * ## 文字コードは [TextFile] に任せる
 *
 * ここで自前の判定を持つと、同じファイルが検索と本文（開いたときの表示）で
 * 違う文字に見えることになる ── `TextFile.read` は復号→符号化の往復でしか
 * 文字コードを決めないので、必ずそちらへ委ねる。
 *
 * ## 打ち切りは2種類ある
 *
 * 上限（件数・ファイル数）に当たったときの [Outcome.truncated] と、
 * 呼び出し側の都合（画面が閉じた等）で止めた [Outcome.cancelled] は別物。
 * 混ぜると「多すぎて切った」のか「頼まれてやめた」のか区別が付かなくなる。
 */
object ProjectSearch {

    /** 名前で丸ごと外すディレクトリ。設定には出さない（v1 の範囲を広げない）。 */
    private val SKIP_DIRS = setOf(".git", ".gradle", "node_modules", "build", ".idea")

    /** 先頭何バイトを見てバイナリ判定するか。 */
    private const val BINARY_SNIFF_BYTES = 8192

    /**
     * 行の区切り。**エンジンと同じ3つを数える。**
     *
     * Sora は `\n` / `\r\n` / **`\r` 単独**のどれでも行を割る
     * （`text/InsertTextHelper.java:88-92`）。ここで `\n` だけを数えると、
     * CR だけで改行されたファイルで**行番号が本文とずれる** ── 結果を押して飛んだ先が
     * 一致した行ではなくなる。**行番号を出す物差しは1本でなければならない。**
     */
    private val LINE_BREAK = Regex("\r\n|\r|\n")

    /**
     * @param line       **1 始まり**
     * @param column     元の行の中での一致の開始位置（**0 始まり**）
     * @param length     一致した長さ。**語の長さとは限らない** ── 正規表現では
     *   当たった範囲が語より長くも短くもなるので、画面が印を付けるにはこれが要る
     * @param text       表示用の切れ端。`…` は付けない（付けるのは画面の側）
     * @param textStart  [text] の先頭が元の行の何文字目か（0 始まり）
     * @param lineLength 元の行の長さ
     */
    data class Hit(
        val file: File,
        val line: Int,
        val column: Int,
        val length: Int,
        val text: String,
        val textStart: Int,
        val lineLength: Int,
    )

    /**
     * @param maxFiles  中身を読んで検索するファイル数の上限
     * @param maxListed 候補として**集める**ファイル数の上限。[maxFiles] とは別に持つ ──
     *   集めた中にはバイナリや大きすぎて飛ばすものが混ざるので、同じ数にすると読む前に足りなくなる
     */
    data class Limits(
        val maxHits: Int = 500,
        val maxFiles: Int = 5000,
        val maxFileBytes: Long = 2L * 1024 * 1024,
        val maxTextLength: Int = 200,
        val maxListed: Int = 20_000,
    )

    /**
     * @param truncated 上限に当たって打ち切った
     * @param cancelled `cancelled()` が true を返したので途中でやめた
     * @param error     パターンが読めなかった理由（正常なら null）
     */
    data class Outcome(
        val hits: List<Hit>,
        val filesScanned: Int,
        val truncated: Boolean,
        val cancelled: Boolean,
        val error: String?,
    )

    fun search(
        root: File,
        query: SearchQuery,
        limits: Limits = Limits(),
        cancelled: () -> Boolean = { false },
    ): Outcome {
        if (query.isEmpty()) return Outcome(emptyList(), 0, false, false, null)

        val pattern = try {
            buildPattern(query)
        } catch (e: PatternSyntaxException) {
            return Outcome(emptyList(), 0, false, false, e.message ?: "invalid pattern")
        }

        val candidates = ArrayList<File>()
        val listedAll = collectFiles(root, candidates, limits.maxListed, cancelled)
        // **集めている途中でもやめる。** 大きな木を集め切ってからでは、打ち替えるたびに古い走査が残る。
        if (cancelled()) return Outcome(emptyList(), 0, false, true, null)
        candidates.sortBy { it.path }

        val hits = ArrayList<Hit>()
        var filesScanned = 0
        var truncated = !listedAll
        var wasCancelled = false

        outer@ for ((index, file) in candidates.withIndex()) {
            // ファイル1本ごとに1回は見る ── 1本の中で長く回り続けない。
            if (cancelled()) {
                wasCancelled = true
                break
            }
            if (file.length() > limits.maxFileBytes) continue
            val head = readHead(file) ?: continue
            // **UTF-16 の英数字は必ず `0x00` を含む**ので、BOM が付いていれば `0x00` では判定せず、
            // 本文を開くときと同じ [TextFile] に読ませる。UTF-16 として読めなかったものだけバイナリとして外す。
            val utf16 = TextFile.Bom.UTF16LE.matches(head) || TextFile.Bom.UTF16BE.matches(head)
            if (!utf16 && head.contains(0)) continue

            val document = runCatching { TextFile.read(file, limits.maxFileBytes) }.getOrNull() ?: continue
            if (utf16 && document.bom != TextFile.Bom.UTF16LE && document.bom != TextFile.Bom.UTF16BE) continue
            filesScanned++

            for ((lineIdx, line) in LINE_BREAK.split(document.text).withIndex()) {
                val matcher = pattern.matcher(line)
                while (matcher.find()) {
                    if (hits.size >= limits.maxHits) {
                        truncated = true
                        break@outer
                    }
                    hits.add(
                        buildHit(
                            file, lineIdx + 1, matcher.start(), matcher.end() - matcher.start(),
                            line, limits.maxTextLength
                        )
                    )
                }
            }

            if (filesScanned >= limits.maxFiles && index < candidates.size - 1) {
                truncated = true
                break
            }
        }

        return Outcome(hits, filesScanned, truncated, wasCancelled, null)
    }

    /**
     * 正規表現が単語単位より勝つ（[SearchQuery.wholeWord] の Javadoc の約束）ので、
     * [SearchQuery.regex] が真なら [SearchQuery.wholeWord] は無視する。
     */
    private fun buildPattern(query: SearchQuery): Pattern {
        val flags = if (query.caseSensitive()) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val body = if (query.regex()) {
            query.pattern()
        } else {
            val literal = Pattern.quote(query.pattern())
            if (query.wholeWord()) "\\b$literal\\b" else literal
        }
        return Pattern.compile(body, flags)
    }

    private fun buildHit(
        file: File,
        line: Int,
        column: Int,
        length: Int,
        lineText: String,
        maxTextLength: Int,
    ): Hit {
        val lineLength = lineText.length
        if (lineLength <= maxTextLength) return Hit(file, line, column, length, lineText, 0, lineLength)
        val start = maxOf(0, minOf(column - 40, lineLength - maxTextLength))
        return Hit(
            file, line, column, length,
            lineText.substring(start, start + maxTextLength), start, lineLength
        )
    }

    /**
     * [root] の下から、除外ディレクトリとシンボリックリンクを飛ばして通常ファイルを集める。
     * 並び替えは呼び出し側（[search]）がまとめて行うので、ここでの順は問わない。
     *
     * **ディレクトリへ入る前に毎回 [cancelled] を見て、[limit] 件を超えては貯めない。**
     *
     * @return 集め切ったら true。上限に当たったか、やめさせられたら false
     */
    private fun collectFiles(dir: File, out: MutableList<File>, limit: Int, cancelled: () -> Boolean): Boolean {
        if (cancelled()) return false
        val children = dir.listFiles() ?: return true
        for (child in children) {
            if (child.isDirectory) {
                if (child.name in SKIP_DIRS) continue
                if (isSymlinkDir(child)) continue
                if (!collectFiles(child, out, limit, cancelled)) return false
            } else if (child.isFile) {
                if (out.size >= limit) return false
                out.add(child)
            }
        }
        return true
    }

    /**
     * [dir] 自身がディレクトリへのシンボリックリンクか。
     *
     * 親を先に正規化し、そこへ [dir] の名前をそのまま繋いだ「もし実体ならこうなるはず」の
     * パスと、実際に辿った [File.canonicalPath] を比べる ── リンクなら後者が別の場所を指す。
     */
    private fun isSymlinkDir(dir: File): Boolean {
        val parent = dir.parentFile ?: return false
        val canonicalParent = runCatching { parent.canonicalFile }.getOrElse { return false }
        val expected = File(canonicalParent, dir.name).path
        val actual = runCatching { dir.canonicalPath }.getOrElse { return true }
        return expected != actual
    }

    /**
     * 先頭 [BINARY_SNIFF_BYTES] バイト。読めなければ null（飛ばす）。
     * ここに `0x00` があればバイナリと見なす ── ただし UTF-16 の BOM が付いていれば別（[search]）。
     */
    private fun readHead(file: File): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(BINARY_SNIFF_BYTES)
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            buffer.copyOf(total)
        }
    }.getOrNull()
}
