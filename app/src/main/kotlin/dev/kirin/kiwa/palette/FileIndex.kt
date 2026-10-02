package dev.kirin.kiwa.palette

import java.io.File

/**
 * パレットのファイルモードが引く一覧を作る。
 *
 * ## 引き出しのツリーと違うもの
 *
 * 引き出し（`FileTreeView`）は**1階層ずつ開いて辿る**道具で、開いた枝しか知らない。
 * パレットは名前を打って当てる道具なので、**先に全部見ておく**必要がある。
 * 同じ木を見ているが、要るものが逆 ── だから畳まずに別で持つ。
 *
 * ## 上限を先に決める
 *
 * この端末の `/sdcard` を無条件に舐めると、写真とキャッシュで数万件になる。
 * **打ち切ったことを黙らない**（[Result.truncated]）── 出ないファイルがあるのに
 * 一覧が普通に見えると、「そのファイルは無い」と読んでしまう。
 */
object FileIndex {

    /** 拾うファイル数の上限。 */
    const val LIMIT = 4000

    /** 潜る深さの上限。 */
    const val MAX_DEPTH = 10

    /**
     * 名前で丸ごと外すディレクトリ。
     *
     * **隠しファイルを出す設定にしても外す。** ここに入っているのは
     * 「人が書いたのではないもの」で、名前を打って開きに行く対象ではない
     * （`build/` の中の生成物が一覧の大半を占めると、上限に達して本物が押し出される）。
     * 設定に出すかどうかは、実際に使って足りなくなってから決める。
     */
    val SKIP = setOf(".git", ".gradle", ".idea", "node_modules", "build", "__pycache__")

    /**
     * [files] は上から順。[truncated] が真なら件数の上限で打ち切っている。
     * [tooDeep] が真なら、深さの上限より下にあるディレクトリを見ていない（[SKIP] で外したものは数えない）。
     */
    data class Result(val files: List<File>, val truncated: Boolean, val tooDeep: Boolean = false) {
        companion object {
            val EMPTY = Result(emptyList(), false)
        }
    }

    /**
     * [root] の下を**浅い方から**集める。
     *
     * 深さ優先だと、上限に達したときに最初の枝の奥だけで埋まる ──
     * 浅い方から入れておけば、打ち切っても「よく開くもの」は残る。
     */
    fun scan(
        root: File,
        showHidden: Boolean,
        limit: Int = LIMIT,
        maxDepth: Int = MAX_DEPTH
    ): Result {
        if (!root.isDirectory) return Result.EMPTY
        val out = ArrayList<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        // symlink で同じ所へ戻ることがある（この端末の `/sdcard` 自体が symlink）。
        // 実体で覚えておかないと、同じファイルが一覧に何度も並ぶ。
        val visited = HashSet<String>()
        queue.add(root to 0)
        visited.add(canonical(root))
        var tooDeep = false

        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (!showHidden && child.isHidden) continue
                if (child.isDirectory) {
                    if (child.name in SKIP) continue
                    if (depth + 1 > maxDepth) {
                        tooDeep = true
                        continue
                    }
                    if (visited.add(canonical(child))) queue.add(child to depth + 1)
                } else {
                    if (out.size >= limit) return Result(out, true, tooDeep)
                    out.add(child)
                }
            }
        }
        return Result(out, false, tooDeep)
    }

    /**
     * パレットに添える注記。**件数と深さのどちらで省いたかを分けて書く** ──
     * どちらも「出ないファイルがある」だが、探しに行く先が違う。省いていなければ空。
     */
    fun note(result: Result): String = listOfNotNull(
        "上限 $LIMIT 件で打ち切り".takeIf { result.truncated },
        "$MAX_DEPTH 階層より深い所は見ていない".takeIf { result.tooDeep },
    ).joinToString(" / ")

    /**
     * 一覧に出す道。**根より下だけを出す** ── `/storage/emulated/0/…` が毎行の頭に付くと、
     * 狭い画面では違いのある部分が右へ押し出されて読めない。
     */
    fun relativePath(root: File, file: File): String {
        val base = root.absolutePath.removeSuffix("/")
        val path = file.absolutePath
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else path
    }

    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}
