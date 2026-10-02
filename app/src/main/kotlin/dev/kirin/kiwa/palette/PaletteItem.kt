package dev.kirin.kiwa.palette

import dev.kirin.kiwa.command.Commands
import java.io.File

/**
 * パレットに並ぶ1行。**何を出すかと、選ばれたら何をするか**だけを持つ。
 *
 * 画面（`ui/PaletteView`）はこの並びを描くだけで、中身が
 * コマンドなのかファイルなのか行番号なのかを知らない ── モードが増えても描く側は変わらない。
 */
class PaletteItem(
    val title: String,
    /** 右か下に薄く出す補足。状態（「今: 折り返す」）や場所（相対パス）。 */
    val detail: String,
    /** [Fuzzy] が見る文字列。**表示とは別に持つ** ── 日本語の名前と英語の id の両方で引くため。 */
    val searchText: String,
    /** 今は押せない（[dev.kirin.kiwa.command.Command.available] が false）。**出すが押せない。** */
    val enabled: Boolean,
    val run: () -> Unit
)

/**
 * モードごとの候補を組み立てる。
 *
 * **ここに「パレット用のコマンド」を作らない。** コマンドは [Commands] の表がすべてで、
 * ここがするのは表を並べ替えて文字を足すことだけ ── 表に無いものがパレットに出たら、
 * それは `tools/check-commands.sh` が見ている「口が1本」の外側に道ができたということ。
 */
object PaletteItems {

    /** コマンドモード。**表の並びをそのまま出す**（`forPalette` が並びを持っている）。 */
    fun forCommands(commands: Commands): List<PaletteItem> =
        commands.forPalette().map { command ->
            val state = command.detail
            PaletteItem(
                title = command.label,
                detail = buildString {
                    if (command.planned) append("まだ作っていない · ")
                    else if (state.isNotEmpty()) append(state).append(" · ")
                    append(command.id)
                },
                // id でも引けるようにする ── `>save` は打てるが「ほぞん」はローマ字では打てない。
                searchText = "${command.label} ${command.id}",
                enabled = command.available,
                run = { command.run() }
            )
        }

    /**
     * ファイルモード。**開いているタブを先に出す。**
     *
     * 戻りたい先はたいてい今日開いたファイルで、そこが木の一覧に埋もれると
     * 「開いているのに探す」ことになる。同じファイルは2度出さない。
     */
    fun forFiles(
        openFiles: List<File>,
        indexed: List<File>,
        root: File,
        open: (File) -> Unit
    ): List<PaletteItem> {
        val seen = HashSet<String>()
        val out = ArrayList<PaletteItem>(openFiles.size + indexed.size)
        for (file in openFiles) {
            if (!seen.add(file.absolutePath)) continue
            out.add(fileItem(file, root, "開いている", open))
        }
        for (file in indexed) {
            if (!seen.add(file.absolutePath)) continue
            out.add(fileItem(file, root, null, open))
        }
        return out
    }

    private fun fileItem(file: File, root: File, note: String?, open: (File) -> Unit): PaletteItem {
        val relative = FileIndex.relativePath(root, file)
        return PaletteItem(
            title = file.name,
            detail = if (note != null) "$note · $relative" else relative,
            // 道でも引けるようにする（`ui/pal` で `ui/PaletteView.kt` に当たる）。
            searchText = relative,
            enabled = true,
            run = { open(file) }
        )
    }

    /**
     * 行モード。候補は1件だけ ── 行番号は選ぶものではなく打つもの。
     *
     * **行数を超えた数字は最後の行へ寄せる**（クランプ）。エラーにして何も起きないより、
     * 末尾へ飛んで「そこまでしか無い」と分かる方が速い。まだ数字になっていなければ null。
     */
    fun forLine(query: PaletteQuery, lineCount: Int, go: (Int) -> Unit): PaletteItem? {
        val asked = query.lineNumber() ?: return null
        val line = asked.coerceIn(1, maxOf(lineCount, 1))
        return PaletteItem(
            title = "$line 行目へ",
            detail = if (line != asked) "全 $lineCount 行しかないので末尾へ" else "全 $lineCount 行",
            searchText = "",
            enabled = true,
            run = { go(line) }
        )
    }
}
