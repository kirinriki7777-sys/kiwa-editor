package dev.kirin.kiwa.file

import java.io.File
import java.nio.charset.Charset

/**
 * 名前を付けて保存（`file.saveAs`）の判定。**画面を知らない**ので単体で試験できる。
 *
 * ## 新しいファイル（[NewFile]）との違い
 *
 * 場所の打ち方は同じ（今の根からの相対パスも、`/` で始まる絶対パスも受ける。フォルダは掘らない）。
 * 違うのは**既に在るファイルを断らない**こと ── 別名で保存する先に同じ名前が在るのはよくあるので、
 * 断る代わりに [Target.Overwrite] として返し、**上書きするかは画面が利用者に確認する**。
 * 黙って上書きはしない（新規のつもりで既存を潰すのが一番戻せない、は [NewFile] と同じ）。
 *
 * ## 文字コードは元に合わせる
 *
 * 開いたファイルを別名で保存するときは、**元の文字コード・BOM をそのまま引き継ぐ**。
 * 別名にしただけで Shift_JIS が UTF-8 に化けると、中身を触っていないのに全バイトが変わる。
 * 無題のタブには元が無いので、設定の「文字コードの既定」を使う。
 */
object SaveAs {

    sealed class Target {
        /** ここへ保存できる（まだ何も無い）。 */
        class Ready(val file: File) : Target()

        /** 同じ名前のファイルが在る。**上書きしてよいかを訊いてから**書く。 */
        class Overwrite(val file: File) : Target()

        /** 今開いているファイルそのもの。ふつうの保存と同じ。 */
        class Same(val file: File) : Target()

        /** 保存できない。[reason] はそのまま画面に出す文。 */
        class Rejected(val reason: String) : Target()
    }

    /**
     * 打たれた名前を、保存する先へ翻訳する。**まだ何も書かない。**
     *
     * 打っている最中にも呼べる（欄の下に行き先を出すため）ので、判定は速いものだけにしてある。
     *
     * @param root    相対で打たれたときの起点
     * @param input   名前の欄の中身
     * @param current 今のタブが開いているファイル。無題なら null
     */
    fun resolve(root: File, input: String, current: File?): Target {
        // 前後の空白は打ち間違い（[NewFile] と同じ）。
        val name = input.trim()
        if (name.isEmpty()) return Target.Rejected("名前が要る")
        if (name.endsWith("/")) return Target.Rejected("フォルダには保存できない ── ファイルの名前を打って")

        val target = if (name.startsWith("/")) File(name) else File(root, name)
        val parent = target.parentFile
            ?: return Target.Rejected("場所が決まらない: $name")
        if (!parent.isDirectory) {
            return Target.Rejected("フォルダが無い: ${parent.absolutePath}")
        }
        if (target.isDirectory) {
            return Target.Rejected("同じ名前のフォルダが在る: ${target.absolutePath}")
        }
        if (current != null && sameFile(current, target)) return Target.Same(target)
        if (target.exists()) return Target.Overwrite(target)
        return Target.Ready(target)
    }

    sealed class Prepared {
        /** この形で書けば、打った本文が1文字も欠けない。 */
        class Ready(val document: TextFile.Document) : Prepared()

        /** 書けない。[reason] はそのまま画面に出す文。 */
        class Rejected(val reason: String) : Prepared()
    }

    /**
     * 書き込む形を決める。**書けない文字が1つでもあれば断る。**
     *
     * `String.toByteArray(charset)` は表せない文字を黙って `?` に置き換える ──
     * 無題のタブに打った絵文字を Shift_JIS で保存すると、**保存は成功したように見えて本文が欠ける**。
     * だから書く前に確かめる。
     *
     * @param source 今のタブが開いているファイル。無題なら null
     * @param defaultCharset 無題のときに使う文字コードの名前（設定の「文字コードの既定」）
     */
    fun prepare(target: File, text: String, source: TextFile.Document?, defaultCharset: String): Prepared {
        val charset: Charset
        val bom: TextFile.Bom
        if (source != null) {
            charset = source.charset
            bom = source.bom
        } else {
            charset = runCatching { Charset.forName(defaultCharset) }.getOrNull()
                ?: return Prepared.Rejected("文字コードが分からない: $defaultCharset")
            bom = TextFile.Bom.NONE
        }
        val lost = firstUnencodable(text, charset)
        if (lost != null) {
            return Prepared.Rejected("「$lost」は ${charset.name()} で書けない ── 保存していない")
        }
        return Prepared.Ready(
            TextFile.Document(target, text, charset, bom, source?.fellBackToBytes ?: false)
        )
    }

    /** [charset] で表せない最初の文字。全部表せれば null。サロゲート対は1文字として返す。 */
    internal fun firstUnencodable(text: String, charset: Charset): String? {
        val encoder = charset.newEncoder()
        if (encoder.canEncode(text)) return null
        var i = 0
        while (i < text.length) {
            val size = Character.charCount(text.codePointAt(i))
            val one = text.substring(i, i + size)
            if (!encoder.canEncode(one)) return one
            i += size
        }
        // 1文字ずつなら全部書ける（状態を持つ符号化の組み合わせ）。ここでは断らない。
        return null
    }

    private fun sameFile(a: File, b: File): Boolean =
        runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(a.absolutePath == b.absolutePath)
}
