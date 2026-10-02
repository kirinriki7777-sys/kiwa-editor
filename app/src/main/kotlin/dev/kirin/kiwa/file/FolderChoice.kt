package dev.kirin.kiwa.file

import java.io.File

/**
 * 保存先のフォルダを選ぶ画面の中身。**画面を知らない**ので単体で試験できる。
 *
 * ## なぜ要るか
 *
 * 名前を付けて保存は「今の根」からの相対か、`/` で始まる絶対パスを打つしかなかった。
 * 根は開いているタブの親か最後の場所なので、**別の場所へ保存するには道を全部覚えて打つ**ことになる。
 * 入力に加えて、フォルダを辿って保存先を選べるようにする。
 *
 * ## 読めないフォルダを空に見せない
 *
 * `/storage` の一覧を取得できないことがある。
 * `listFiles()` が null を返したときに空の一覧を出すと「何も無い」と見分けが付かないので、
 * [Listing.readable] で分けて返す。SD カードへは一覧を辿らず、端末が知らせる置き場（[Place]）から入る。
 */
object FolderChoice {

    /** 置き場の入口。内部ストレージや SD カード。[label] は端末が付けた名前（「SD カード」など）。 */
    class Place(val label: String, val dir: File)

    class Listing(
        val dir: File,
        /** 1つ上。根なら null。 */
        val parent: File?,
        /** 中のフォルダ。名前順。ファイルは出さない（保存先を選ぶ画面なので）。 */
        val folders: List<File>,
        /** 中を読めたか。false なら [folders] は空だが「無い」のではない。 */
        val readable: Boolean,
    )

    fun list(dir: File, showHidden: Boolean): Listing {
        val children = dir.listFiles()
        val folders = children.orEmpty()
            .filter { it.isDirectory && (showHidden || !it.isHidden) }
            .sortedBy { it.name.lowercase() }
        return Listing(dir, dir.parentFile, folders, children != null)
    }

    /**
     * 入口を並べる形にする。**同じ場所は1つにし、在るものだけ残す**
     * （取り外した SD カードの道が残っていても、押して空振りするだけになる）。
     */
    fun places(candidates: List<Place>): List<Place> =
        candidates
            .filter { it.dir.isDirectory }
            .distinctBy { it.dir.absolutePath }
}
