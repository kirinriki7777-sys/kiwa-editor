package dev.kirin.editoradapter;

/**
 * 検索の条件。<b>パターンと3つのフラグを1つの値にまとめてある。</b>
 *
 * <p><b>なぜ束ねるか。</b> 3つとも {@code boolean} なので、引数で並べると
 * {@code search(pattern, false, true, false)} のように<b>呼ぶ側でしか意味が読めない</b>形になり、
 * 順番を入れ替えても型では落ちない。ここに名前を付けておけば、
 * 「大文字小文字を区別する検索」を組み立てた場所と使う場所が離れても読める。
 *
 * <p><b>同値比較ができることが要る。</b> 検索欄はフラグを触るたびに引き直すが、
 * <b>条件が変わっていなければ引き直さない</b> ── 引き直すと Sora が検索スレッドを
 * 立て直し、結果が届くまでの間だけ件数が 0 に見える（{@link EditorSearch#isSearching()}）。
 */
public final class SearchQuery {

    private final String pattern;
    private final boolean caseSensitive;
    private final boolean wholeWord;
    private final boolean regex;

    public SearchQuery(String pattern, boolean caseSensitive, boolean wholeWord, boolean regex) {
        this.pattern = pattern == null ? "" : pattern;
        this.caseSensitive = caseSensitive;
        this.wholeWord = wholeWord;
        this.regex = regex;
    }

    public String pattern() {
        return pattern;
    }

    /** 大文字小文字を区別するか。 */
    public boolean caseSensitive() {
        return caseSensitive;
    }

    /**
     * 単語として一致するものだけを拾うか。
     *
     * <p><b>{@link #regex()} と同時には効かない</b> ── どちらも「パターンの読み方」を
     * 決めるもので、エンジンによっては片方しか選べない（Sora の {@code SearchOptions.type} は
     * 通常 / 単語 / 正規表現の3択）。<b>正規表現が勝つ</b>ことにしてある。
     */
    public boolean wholeWord() {
        return wholeWord;
    }

    /** パターンを正規表現として読むか。 */
    public boolean regex() {
        return regex;
    }

    /** 引ける形か。空のパターンは検索ではない。 */
    public boolean isEmpty() {
        return pattern.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SearchQuery)) {
            return false;
        }
        SearchQuery that = (SearchQuery) other;
        return caseSensitive == that.caseSensitive
                && wholeWord == that.wholeWord
                && regex == that.regex
                && pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        int result = pattern.hashCode();
        result = 31 * result + (caseSensitive ? 1 : 0);
        result = 31 * result + (wholeWord ? 1 : 0);
        result = 31 * result + (regex ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "SearchQuery{'" + pattern + "'"
                + (caseSensitive ? " Aa" : "")
                + (wholeWord ? " word" : "")
                + (regex ? " regex" : "")
                + "}";
    }
}
