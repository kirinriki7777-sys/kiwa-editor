package dev.kirin.editoradapter;

import java.util.List;

/**
 * IME とエディタの境界。
 *
 * <p><b>この I/F の存在理由。</b> Android のコードエディタで日本語入力が壊れるのは、
 * エンジンの出来が悪いからではなく、<b>IME が送ってくるものを誰が引き受けるか決まっていない</b>から。
 * ここに挙げた4つは、Sora Editor 0.24.6 を実機で測って出てきた具体的な穴で、
 * どれも<b>エンジンを fork せずアプリ側で塞げる</b>ことが確認できている。
 *
 * <p>実装はエンジンごとに書く。メソッドの Javadoc には
 * 「Sora 0.24.6 ならどう実装するか」を根拠の行番号つきで書いてある ──
 * 別のエンジンへ載せ替えるとき、何を満たせばいいかがこの I/F だけで分かるようにするため。
 */
public interface ImeBoundary {

    // ------------------------------------------------------------------
    // 責務1: IME が付けた装飾を受けて描く
    // ------------------------------------------------------------------

    /**
     * 未確定文字列の装飾を差し替える。IME から届くたびに呼ぶ。
     *
     * <p><b>Sora 0.24.6 での実装:</b> 装飾の範囲を行・桁へ直し、
     * {@code HighlightTextContainer} に積んで {@code CodeEditor.setHighlightTexts()} を呼ぶ
     * （widget/CodeEditor.java:4323）。色は {@code ConstColor(int)} で ARGB をそのまま渡せる
     * （lang/styling/color/ConstColor.kt）。描画は「Draw highlight text background」
     * （widget/EditorRenderer.java:1286）が背景と枠として塗る。
     *
     * <p>composing の下線（widget/EditorRenderer.java:1516）とは別処理なので、
     * 下線を保ったまま背景を重ねられる。
     *
     * <p><b>エンジンのバッファに装飾を書き込んではいけない。</b> Sora の {@code Content} は
     * char ベースでスパンを保持できず（text/ContentLine.java:40）、
     * 書き込もうとすると必ず落ちる。装飾は<b>バッファの外</b>に持つ。
     */
    void setComposingDecorations(List<ComposingDecoration> decorations);

    /** 装飾を消す。確定・中断・フォーカス喪失で呼ぶ。 */
    void clearComposingDecorations();

    // ------------------------------------------------------------------
    // 責務2: 変換1回を編集1単位にまとめる
    // ------------------------------------------------------------------

    /**
     * 変換の1区切りを開く。最初の {@code setComposingText} で呼ぶ。
     *
     * <p><b>なぜ要るか。</b> IME は未確定文字列を更新するたびに
     * {@code beginBatchEdit} / {@code endBatchEdit} を開いて閉じる（実測: 1セッションで614回ずつ）。
     * エンジンはその1組を Undo の1単位として扱うので、<b>変換候補を1つ選ぶたびに Undo 単位が積まれる</b>。
     * Ctrl+Z が変換の履歴を1コマずつ逆再生する形になる。
     *
     * <p><b>Sora 0.24.6 での実装:</b> composing の間、IME の begin/end を
     * {@code Content} へ<b>流さない</b>。開始時と終了時に1回ずつだけ
     * {@code Content.beginBatchEdit()} / {@code endBatchEdit()} を呼ぶ（text/Content.java:696, 707）。
     * ネストのカウンタが 0 にならない限り単位は切れない（text/Content.java:709）。
     *
     * <p><b>ただし握るだけでは足りない。</b> {@link #notifySelectionToIme()} を読むこと。
     */
    void beginCompositionScope();

    /** 変換の1区切りを閉じる。確定・中断で呼ぶ。開いた分だけ必ず閉じる。 */
    void endCompositionScope();

    /**
     * カーソル位置と変換範囲を IME へ通知する。
     *
     * <p><b>なぜ独立した口が要るか。</b> エンジンによっては、この通知が
     * batch edit の終了に相乗りしている。Sora 0.24.6 は
     * {@code endBatchEdit()} がネスト 0 のときに {@code updateSelection()} を呼び
     * （widget/EditorInputConnection.java:405-408）、それが
     * {@code InputMethodManager.updateSelection()} で位置と変換範囲を IME へ渡している
     * （widget/CodeEditor.java:4493）。
     *
     * <p>つまり責務2のために batch edit を握ると、<b>この通知も一緒に止まる</b>。
     * IME が変換対象の位置を見失うので、握った側が代わりに呼ぶ必要がある。
     * Sora なら {@code CodeEditor.updateSelection()} が {@code protected} なので
     * サブクラスから呼べる（widget/CodeEditor.java:4475）。
     */
    void notifySelectionToIme();

    // ------------------------------------------------------------------
    // 責務3: 変換中はエディタのキーバインドを黙らせる
    // ------------------------------------------------------------------

    /**
     * エディタ自身のキーバインドの有効・無効を切り替える。composing の間は false にする。
     *
     * <p><b>なぜ要るか。</b> エディタのキー処理は composing を知らないことが多い
     * （Sora 0.24.6 の {@code EditorKeyEventHandler} には composing の語が1つも無い）。
     * Shift+←→ は文節の伸縮、Space は変換、Enter は確定、Tab は候補移動 ──
     * どれも日本語入力で使うキーが、そのままエディタの機能として処理されうる。
     *
     * <p>実測では IME がほとんどのキーの DOWN を消費するため、実際にエディタへ届いたのは
     * {@code KEYCODE_TAB} だけだった。<b>それでも個別に塞がず全部止める</b> ──
     * 測ったのは IME 2つ・試したキーの範囲だけで、他の IME が素通しする可能性が残るため。
     *
     * <p><b>Sora 0.24.6 での実装:</b> {@code CodeEditor.canHandleKeyBinding()} を override して
     * composing 中は無条件で false を返す（widget/CodeEditor.java:1022、{@code protected}）。
     */
    void setKeyBindingsEnabled(boolean enabled);

    // ------------------------------------------------------------------
    // 責務4: 物理キーボードでも composing 経路を使わせる
    // ------------------------------------------------------------------

    /**
     * 物理キーボード接続時にソフトキーボードを抑止する挙動を切り替える。
     *
     * <p><b>これを false にしないと日本語入力が成立しない。</b> 抑止が有効だと
     * エディタは {@code EditorInfo.inputType} に {@code TYPE_NULL} を名乗り、
     * IME は composing を使わず1文字ずつ直接確定する経路へ落ちる。
     * 変換が一切できなくなるが、<b>英数字は普通に打てるので気づきにくい</b>。
     *
     * <p><b>Sora 0.24.6 での実装:</b>
     * {@code CodeEditor.setDisableSoftKbdIfHardKbdAvailable(false)} を
     * <b>エディタが最初にフォーカスを取る前に</b>呼ぶ。
     */
    void setSoftKeyboardSuppressedWithHardwareKeyboard(boolean suppressed);

    // ------------------------------------------------------------------
    // 状態
    // ------------------------------------------------------------------

    /** 未確定文字列を保持しているか。 */
    boolean isComposing();

    /**
     * 未確定文字列の範囲。composing 中でなければ null。
     *
     * <p><b>境界はこの状態を自前で持ってはいけない。</b> エンジンが同じ状態を
     * 内部で参照しているため（Sora なら {@code CodeEditor.hasComposingText()} など）、
     * 二重に持つと必ず食い違う。<b>読むだけ</b>にする。
     */
    TextRange composingRange();
}
