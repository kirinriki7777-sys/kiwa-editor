package dev.kirin.editoradapter;

/**
 * 本文の検索と置換。<b>自作しない</b> ── エンジンが持っているものを包む。
 *
 * <p><b>なぜ {@link EditorEngine} 本体に足さず別の I/F にするか。</b>
 * {@link EditorEngine} が握るのは「IME と物理キーボードが壊れないために要るもの」だけ、
 * という線を引いてある。検索はその外側の機能で、<b>エンジンが持っていなければ
 * アプリ側で書けるもの</b>でもある ── 別の I/F にしておけば、
 * 載せ替えた先が検索を持たないときに<b>ここだけ自前の実装を挿せる</b>。
 *
 * <p><b>それでも境界の中に置くのは、実装を app へ置けないから。</b>
 * Sora の {@code EditorSearcher} は正規表現・単語単位・1件/全件置換・次へ/前へ・
 * 一致件数まで公開している。それを使う以上、Sora の型に触るコードが要る ──
 * {@code :app} には {@code io.github.rosemoe} を 1 件も出さない決めなので
 * （{@code tools/check-boundary.sh}）、包む場所は {@code :engine-sora} になる。
 *
 * <h2>★「探している最中」と「0 件」は別物</h2>
 *
 * <p>エンジンは検索を別スレッドで走らせる（Sora 0.24.6 の
 * {@code EditorSearcher.SearchRunnable}）。数え終わるまで件数は 0 で、
 * <b>「当たらなかった」と見分けが付かない</b>。検索はまさに 0 件が正常値になる機能なので、
 * そこを混ぜると「当たらない」と出ているものが「まだ数えている」なのか
 * 「本当に無い」なのか、画面からもログからも読めなくなる。
 *
 * <p>だから {@link #isSearching()} を I/F に出してある。呼ぶ側は
 * <b>探している間は件数を出さない</b>こと。
 */
public interface EditorSearch {

    /**
     * 検索を始める。結果は<b>すぐには揃わない</b>
     * （{@link #setOnResultListener(Runnable)} で受ける）。
     *
     * <p>受け付けなかったときは {@code false} を返し、理由が {@link #error()} に入る ──
     * <b>例外を投げない</b>。打っている途中の正規表現は壊れているのが普通で
     * （{@code (} まで打った時点など）、そのたびに例外が飛ぶ形だと呼ぶ側が
     * try-catch で囲むことになる。
     *
     * @return 受け付けたら true。空のパターン / 壊れた正規表現なら false
     */
    boolean start(SearchQuery query);

    /**
     * 検索をやめる。<b>ハイライトも消える。</b>
     *
     * <p>検索欄を閉じるときのほか、<b>本文が別物になったとき</b>にも呼ぶこと ──
     * タブを切り替えると中身が丸ごと変わるので、前の本文で数えた位置は
     * そのまま別の場所を指す（エンジンは気づかない）。
     */
    void stop();

    /** 検索中の条件がある（＝ハイライトが出ている）か。 */
    boolean hasQuery();

    /**
     * まだ数え終わっていないか。<b>これが true の間、{@link #matchCount()} は 0 を返す。</b>
     * 「当たらない」と出す前にここを見ること。
     */
    boolean isSearching();

    /** 一致した件数。条件が無い / 数え終わっていないときは 0。 */
    int matchCount();

    /**
     * 今カーソルが乗っている一致の番号。<b>1 始まり</b>（画面に「3 / 17」と出すため）。
     * どの一致にも乗っていなければ 0。
     */
    int currentIndex();

    /** 受け付けなかった理由。正常なら null。 */
    String error();

    /**
     * 次の一致へ飛ぶ。末尾まで行ったら先頭へ回る。
     *
     * @return 飛んだら true（1件も無ければ false）
     */
    boolean next();

    /** 前の一致へ飛ぶ。先頭まで行ったら末尾へ回る。 */
    boolean previous();

    /**
     * 今乗っている一致を置き換える。<b>乗っていなければ次へ飛ぶだけ</b>
     * （エンジンの作法をそのまま通す ── 押すたびに1件ずつ進むのはこの形）。
     *
     * <p><b>{@link #error()} が null でない間は何もしない。</b> 最後に渡された条件が読めなかったのに、
     * その前に通った条件で書き換えると、画面に出ている条件と違う所が変わる。
     */
    void replaceCurrent(String replacement);

    /**
     * 全部置き換える。<b>これも非同期</b>で、終わると [whenDone] が呼ばれる。
     *
     * <p><b>置き換えたあとの検索結果は古い。</b> 位置がずれるだけでなく、
     * 置換後の文字列がまた一致することもある（{@code a} を {@code aa} にする等）。
     * 呼ぶ側は [whenDone] で引き直すか、検索を止めること。
     *
     * <p><b>{@link #error()} が null でない間は置き換えない</b>（{@link #replaceCurrent} と同じ）。
     *
     * @param whenDone 終わったら呼ばれる。null 可
     */
    void replaceAll(String replacement, Runnable whenDone);

    /**
     * 数え終わったときに呼ばれるものを1つ登録する。{@code null} で外す。
     *
     * <p>{@link EditorEngine#setOnChangeListener(Runnable)} と同じ形 ──
     * <b>「変わった」という合図だけ</b>を渡し、件数は受け取った側が
     * {@link #matchCount()} で聞く。{@link #stop()} でも呼ばれる。
     */
    void setOnResultListener(Runnable listener);
}
