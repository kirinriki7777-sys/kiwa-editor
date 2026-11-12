package dev.kirin.kiwa.file

import dev.kirin.kiwa.view.ViewMode

/**
 * 起動した時に続きから開くファイル（最後に書いていたワープロのファイル）。
 * 覚えるのは絶対パス・カーソルの位置（先頭からの文字数）・その時のモードと縦横。
 * モードを覚えるのはこの1件だけで、ほかのタブは今まで通り拡張子の既定。
 */
data class ResumePoint(val path: String, val offset: Int, val mode: ViewMode, val vertical: Boolean = false) {

    /** 外で書き換わって短くなっていても落ちないよう、位置を本文の長さへ収める。 */
    fun offsetIn(length: Int): Int = offset.coerceIn(0, length)

    companion object {
        /** 保存してあった値から復元する。縦横の欄が無い旧版は横。足りない・読めないものがあれば null（今と同じ起動）。 */
        fun restore(path: String?, offset: Int, modeName: String?, vertical: Boolean = false): ResumePoint? {
            val mode = ViewMode.entries.firstOrNull { it.name == modeName } ?: return null
            if (path.isNullOrEmpty()) return null
            return ResumePoint(path, offset, mode, vertical && mode == ViewMode.WORDPROCESSOR)
        }
    }
}
