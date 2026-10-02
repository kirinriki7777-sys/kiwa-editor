package dev.kirin.editoradapter;

/**
 * テキスト中の1点。行・桁と絶対インデックスの両方を持つ。
 *
 * <p>両方を持つ理由は、IME と描画で必要な形が違うため。IME は絶対インデックスで
 * 範囲を指定してくるが、ハイライトの描画は行・桁で行われる。変換は
 * エンジン側が担う（Sora なら {@code Content.getIndexer().getCharPosition(int)}
 * ── text/Content.java:769 と text/CachedIndexer.java:347）。
 */
public final class TextPosition {

    private final int line;
    private final int column;
    private final int index;

    public TextPosition(int line, int column, int index) {
        this.line = line;
        this.column = column;
        this.index = index;
    }

    /** 0 起点の行番号。 */
    public int line() {
        return line;
    }

    /** 0 起点の桁。 */
    public int column() {
        return column;
    }

    /** テキスト先頭からの絶対インデックス。 */
    public int index() {
        return index;
    }

    @Override
    public String toString() {
        return "TextPosition{" + line + ":" + column + " (#" + index + ")}";
    }
}
