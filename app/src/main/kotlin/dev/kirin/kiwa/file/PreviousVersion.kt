package dev.kirin.kiwa.file

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 上書きする直前の中身を、**1世代だけ**アプリ専用の場所 [dir]（`filesDir` の下）に残す。
 * ワープロのタブが既にあるファイルへ書く時だけ使う（保存の手押しも画面を離れた時も）。
 *
 * - **元のファイルの隣には何も作らない**（Syncthing に流れるので）。元のバイトも触らない。
 * - 控えの名前は元の**絶対パスの SHA-256（16進）+ `.prev`**。同じファイルの前の控えは置き換わる。
 *   パスが同じなら名前も同じなので、控えを引くのに索引は要らない。
 * - 数の上限は置かない。控えは「ワープロで上書き保存したことのあるファイル」1つにつき1つで、
 *   1つは [TextFile.MAX_BYTES] 以下。逃げ道は、増えすぎたら更新時刻の古い順に消す。
 */
class PreviousVersion(private val dir: File) {

    /** [original] の控えの置き場（あるとは限らない）。 */
    fun backupOf(original: File): File = File(dir, nameFor(original) + SUFFIX)

    /**
     * [original] の今のバイトを控えへ写し、前の控えを置き換える。**書けなかったら [IOException]**
     * ── 呼び出し側は上書きを止める（控えが無いまま上書きしない）。
     * まだ無いファイル（新しく作る保存）は控えるものが無いので何もしない。
     */
    fun keep(original: File) {
        if (!original.isFile) return
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("控えの置き場を作れない")
        val temp = File.createTempFile(".keep", ".tmp", dir)
        try {
            original.copyTo(temp, overwrite = true)
            if (!temp.renameTo(backupOf(original))) throw IOException("控えを差し替えられない")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** 控えを読んで復号する。無ければ null。 */
    fun read(original: File, preferredCharset: String = "UTF-8"): TextFile.Document? {
        val backup = backupOf(original)
        return if (backup.isFile) TextFile.read(backup, preferredCharset = preferredCharset) else null
    }

    companion object {
        const val SUFFIX = ".prev"

        fun nameFor(original: File): String =
            MessageDigest.getInstance("SHA-256").digest(original.absolutePath.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
