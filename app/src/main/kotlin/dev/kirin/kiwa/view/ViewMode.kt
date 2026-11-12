package dev.kirin.kiwa.view

/**
 * タブごとの見せ方。**ファイルの中身は変えない** ── 切り替えても本文・取り消しの履歴・カーソルは同じ。
 *
 * タブのモードは、開くたびに [defaultFor] が拡張子で決める。覚えているのは起動時の続きから開く1件だけ（`ResumePoint`）。
 */
enum class ViewMode(val label: String) {
    CODE("コード"),
    WORDPROCESSOR("ワープロ");

    fun toggled(): ViewMode = if (this == CODE) WORDPROCESSOR else CODE

    companion object {
        /** `.txt` はワープロ、それ以外（`.md` も）はコード。無題（名前なし）はコード。 */
        fun defaultFor(fileName: String?): ViewMode =
            if (fileName != null && fileName.lowercase().endsWith(".txt")) WORDPROCESSOR else CODE
    }
}
