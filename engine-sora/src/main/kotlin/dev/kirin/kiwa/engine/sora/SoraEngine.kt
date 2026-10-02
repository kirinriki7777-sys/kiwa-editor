package dev.kirin.kiwa.engine.sora

import android.content.Context
import dev.kirin.editoradapter.EditorEngine

/**
 * Sora 実装を作る唯一の口。
 *
 * `:app` がこのモジュールに触れてよいのはここだけで、戻り値は抽象 [EditorEngine]。
 * こうしておくと「`:app` に `io.github.rosemoe` の import が 0 件」が grep で検査できる
 * （`tools/check-boundary.sh`）── 差し替え可能性を設計図でなく検査で守るため。
 */
object SoraEngine {

    /**
     * @param trace 境界で起きたことの記録先。null なら一切記録しない（呼び出しも起きない）。
     *              実機ゲートで使う。`:app` の debug ビルドだけが渡す。
     */
    @JvmStatic
    @JvmOverloads
    fun create(context: Context, trace: ImeTrace? = null): EditorEngine =
        SoraEditorEngine(context, trace)
}
