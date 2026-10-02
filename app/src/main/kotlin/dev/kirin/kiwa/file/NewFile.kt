package dev.kirin.kiwa.file

import java.io.File
import java.io.IOException

/**
 * 新しいファイルを作る前の判定（`file.new`）。**画面を知らない**ので単体で試験できる。
 *
 * ## 「新しいタブ」とは別物
 *
 * `file.newTab` は保存先の無い空のタブを開く。こちらは**実体のあるファイルを作る**ので、
 * 保存先がその場で決まる ── 表Aで層①の「新規」と「タブ」が別の行なのはそのため。
 *
 * ## 場所は打って決める
 *
 * 名前の欄は**今の根からの相対パスも、`/` で始まる絶対パスも受ける**。
 * 打つための道具なので、`docs/note.md` と打てる方が階層を潜るより速い。
 * 根そのものは画面の「場所を選ぶ…」で潜って選び直せる（2026-10-02、[FolderChoice]）。
 *
 * **フォルダは作らない。** 親が無ければ断る ── 打ち間違えた深い道を黙って掘ると、
 * 気づかないまま似た名前のフォルダが増える。
 */
object NewFile {

    sealed class Result {
        /** ここへ作れる。 */
        class Ready(val file: File) : Result()

        /** 作れない。[reason] はそのまま画面に出す文。 */
        class Rejected(val reason: String) : Result()
    }

    /**
     * 打たれた名前を、作る先へ翻訳する。**まだ何も作らない。**
     *
     * 打っている最中にも呼べる（欄の下に行き先を出すため）ので、
     * **判定は速いものだけ**にしてある（`exists` / `isDirectory` の2つ）。
     *
     * @param root  相対で打たれたときの起点
     * @param input 名前の欄の中身
     */
    fun resolve(root: File, input: String): Result {
        // 前後の空白は打ち間違い。名前の一部として残すと、
        // 母艦から見たときに見えない差になる。
        val name = input.trim()
        if (name.isEmpty()) return Result.Rejected("名前が要る")
        if (name.endsWith("/")) return Result.Rejected("フォルダは作らない ── ファイルの名前を打って")

        val target = if (name.startsWith("/")) File(name) else File(root, name)
        val parent = target.parentFile
            ?: return Result.Rejected("場所が決まらない: $name")
        if (!parent.isDirectory) {
            return Result.Rejected("フォルダが無い: ${parent.absolutePath}")
        }
        if (target.exists()) {
            // **上書きしない。** 新規のつもりで既存を潰すのが一番戻せない。
            return Result.Rejected("同じ名前のものが在る: ${target.absolutePath}")
        }
        return Result.Ready(target)
    }

    /**
     * 空のファイルを作る。**作れたら null、駄目ならその理由**を返す。
     *
     * [resolve] を通してから呼ぶこと ── ここは書き込みだけを見る。
     */
    fun create(file: File): String? = try {
        // 判定と作成の間に誰かが作っていたら false が返る（母艦から Syncthing で届く）。
        if (file.createNewFile()) null else "同じ名前のものが在る: ${file.absolutePath}"
    } catch (e: IOException) {
        "作れなかった: ${e.message}"
    } catch (e: SecurityException) {
        "作れなかった: ${e.message}"
    }
}
