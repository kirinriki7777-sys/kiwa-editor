package dev.kirin.editoradapter;

/**
 * IME が未確定文字列に付けた装飾の1区間。
 *
 * <p><b>なぜこの型が要るか。</b> 日本語 IME は「変換対象の文節」と「それ以外の未確定部分」を
 * 塗り分けて渡してくるが、多くの Android 用エディタはそれを捨てる。実測では Gboard が
 * {@code BackgroundColorSpan} の alpha {@code 0x66} と {@code 0x19} で塗り分けており、
 * その情報が入力先に届いているのに描かれていなかった（症状B）。
 *
 * <p><b>装飾を渡す経路は2本ある。</b> {@code CharSequence} に付いたスパンと、
 * Android 12 で入った {@code TextAttribute}（{@code setComposingText} の3引数版）。
 * 境界は<b>どちらで来ても</b>この型に正規化して受ける ── Sora 0.24.6 は
 * 3引数版のオーバーロードを1つも実装していないため（S1a の受入で確認）、
 * どちらか片方だけを前提にすると取りこぼす。
 *
 * <p>範囲は絶対インデックスで持つ。IME がその形で渡してくるため。
 * 行・桁への変換はエンジン側の責務。
 */
public final class ComposingDecoration {

    /** 色が指定されていないことを表す。エンジンの既定色に任せる。 */
    public static final int COLOR_UNSPECIFIED = 0;

    private final int startIndex;
    private final int endIndex;
    private final int backgroundColor;
    private final int borderColor;

    /**
     * @param startIndex      範囲の開始（テキスト先頭からの絶対インデックス）
     * @param endIndex        範囲の終わり（排他）
     * @param backgroundColor 背景の ARGB 色。{@link #COLOR_UNSPECIFIED} なら既定色
     * @param borderColor     枠の ARGB 色。{@link #COLOR_UNSPECIFIED} なら枠を描かない
     */
    public ComposingDecoration(int startIndex, int endIndex, int backgroundColor, int borderColor) {
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.backgroundColor = backgroundColor;
        this.borderColor = borderColor;
    }

    public int startIndex() {
        return startIndex;
    }

    public int endIndex() {
        return endIndex;
    }

    /** 背景の ARGB 色。{@link #COLOR_UNSPECIFIED} なら未指定。 */
    public int backgroundColor() {
        return backgroundColor;
    }

    /** 枠の ARGB 色。{@link #COLOR_UNSPECIFIED} なら未指定。 */
    public int borderColor() {
        return borderColor;
    }

    public boolean isEmpty() {
        return startIndex >= endIndex;
    }

    @Override
    public String toString() {
        return "ComposingDecoration{" + startIndex + ".." + endIndex
                + " bg=#" + Integer.toHexString(backgroundColor)
                + " border=#" + Integer.toHexString(borderColor) + "}";
    }
}
