package dev.kirin.kiwa.search

/**
 * 検索の状態を1行の文字にする。**画面を知らない**ので単体で試験できる。
 *
 * ## なぜ切り出すか
 *
 * **検索は 0 件が正常値になる機能**で、そこに「まだ数えていない 0」が混ざると
 * 画面からもログからも見分けが付かなくなる ── E10b の欠陥3件目は
 * ログに「候補 0 件」としか出ず、`screencap` で入力欄を見るまで分からなかった。
 *
 * 状態は4つあり（受け付けなかった / 探している / 条件が無い / 数え終わった）、
 * **その組み立てが正しいことは実機を出さずに踏める**。画面の中に `when` を埋めると、
 * 踏めるのは実機だけになる。
 */
object SearchStatus {

    /**
     * @param error     受け付けなかった理由（正常なら null）
     * @param searching **まだ数え終わっていない**。この間は件数を出さない
     * @param hasQuery  検索中の条件がある（＝ハイライトが出ている）
     * @param count     一致した件数
     * @param index     今乗っている一致の番号（1 始まり。乗っていなければ 0）
     */
    fun label(error: String?, searching: Boolean, hasQuery: Boolean, count: Int, index: Int): String = when {
        // 受け付けられなかったときだけ入っている（受け付いた時点で消える）。
        error != null -> error
        // **ここで「当たらない」と書かない。** 数えている最中の 0 は結果ではない。
        searching -> "探している"
        !hasQuery -> ""
        count == 0 -> "当たらない"
        index > 0 -> "$index / $count"
        // 一致はあるが、カーソルはどれにも乗っていない（打った直後・置換の直後）。
        else -> "$count 件"
    }

    /**
     * 置き換えてよいか。**読めない条件が出ている間は断る。**
     *
     * エンジンは壊れた式を受け付けなかったとき、前に通った条件とハイライトを残す
     * （打っている途中の `(` で毎回消えると打てないため）。そのまま置き換えると、
     * 画面の `foo(` ではなく前の `foo` の一致を書き換える ── 表示と実行の条件が食い違う。
     *
     * @param error     受け付けなかった理由（正常なら null）
     * @param searching まだ数え終わっていない
     * @param hasQuery  検索中の条件がある
     */
    fun canReplace(error: String?, searching: Boolean, hasQuery: Boolean): Boolean =
        error == null && !searching && hasQuery

    /**
     * フォルダ全体の検索（E13）の1行。**本文の検索とは主語が違う**ので別にしてある。
     *
     * 本文の検索は「今どれに乗っているか」だが、こちらは**「どこまで数えたか」** ──
     * 止まり方が3通り（数え切った / 多すぎて切った / 頼まれてやめた）あり、
     * どれも件数は出るのに意味が違う。全部を「$hits 件」に畳むと、
     * **先頭だけ見て「これで全部」と読める**。
     *
     * @param truncated 上限に当たって打ち切った
     * @param cancelled 呼んだ側がやめさせた（画面を閉じた等）
     */
    fun projectLabel(
        error: String?,
        searching: Boolean,
        hasQuery: Boolean,
        hits: Int,
        files: Int,
        truncated: Boolean,
        cancelled: Boolean,
    ): String = when {
        error != null -> error
        searching -> "探している"
        !hasQuery -> ""
        cancelled -> "途中でやめた（$hits 件まで）"
        // 打ち切ったのに 0 件を「当たらない」と書くと、見ていない所にも無いと読める。
        hits == 0 && !truncated -> "当たらない"
        truncated -> "多すぎる ── 先頭 $hits 件 / $files ファイル"
        else -> "$hits 件 / $files ファイル"
    }
}
