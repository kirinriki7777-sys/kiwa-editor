package dev.kirin.editoradapter;

/**
 * エディタの配色。
 *
 * <p><b>名前だけを渡す。</b> 実際の色はエンジンが持つ ── エディタの配色は
 * 「文字」「行番号」「現在行」「選択」「変換中の装飾」など数十の役割からなり、
 * それを境界の型として並べると<b>エンジンを差し替えるたびに対応表を作り直すことになる</b>。
 *
 * <p>アプリ側の色（ツールバーや地の色）はアプリが持つ。ここはエディタの中だけ。
 */
public enum EditorTheme {
    // アプリで選べる配色。名前は色の組み合わせを表す。
    /** 墨の地に金。 */
    SUMI(true),
    /** 夜の青。 */
    YORU(true),
    /** 森の緑。 */
    MORI(true),
    /** 深夜の地に紅。 */
    BENI(true),
    /** 紙の地に墨。 */
    KAMI(false),
    /** 白の地に青。 */
    SHIRO(false),
    /** 生成りの地に緑。 */
    KINARI(false),
    /** 霧の灰に紫。 */
    KIRI(false);

    private final boolean dark;

    EditorTheme(boolean dark) {
        this.dark = dark;
    }

    /**
     * 暗い地か。<b>名前で比べずにこちらを見る</b> ── 変換中の装飾の濃さのように、
     * 明暗だけで決まるものがある。暗い配色が1つだった頃は {@code == DARK} で足りたが、
     * 増えたので明暗は配色の側に持たせた。
     */
    public boolean isDark() {
        return dark;
    }
}
