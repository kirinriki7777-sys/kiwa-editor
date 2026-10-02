package dev.kirin.editoradapter;

/**
 * テキスト中の範囲。選択範囲にも composing 範囲にも使う。
 *
 * <p>空（{@code start} と {@code end} が同じ）ならカーソル位置を表す。
 */
public final class TextRange {

    private final TextPosition start;
    private final TextPosition end;

    public TextRange(TextPosition start, TextPosition end) {
        this.start = start;
        this.end = end;
    }

    public TextPosition start() {
        return start;
    }

    public TextPosition end() {
        return end;
    }

    /** 選択が無い（カーソルだけ）か。 */
    public boolean isEmpty() {
        return start.index() == end.index();
    }

    public int length() {
        return end.index() - start.index();
    }

    @Override
    public String toString() {
        return "TextRange{" + start + " -> " + end + "}";
    }
}
