package dev.kirin.kiwa.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Environment
import android.os.storage.StorageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kirin.kiwa.settings.EditorSettings
import java.io.File

/**
 * エクスプローラー（ファイルツリー）。
 *
 * **置き方は画面の幅で変わる**（見た目案 01。2026-09-29）── 横に広いときは本文の左に並べて開いたままにし、
 * 狭いとき（縦向き）は前と同じく被せて、押したら閉じる。前は「常設にすると縦向きで本文が半分になる」
 * ので常に被せていた（案C）。並べるか被せるかは `MainActivity` が決め、このビューは中身だけ持つ。
 *
 * ## 場所と「読めない」
 *
 * 上に**場所**（内部ストレージと SD カード）を並べる。`/storage` の一覧に SD が現れない場合があるため、
 * 場所は Android のボリュームの一覧から引く。
 *
 * **一覧が取れなかったフォルダは「読めない」と出す。** `listFiles()` は読めないと null を返し、
 * 前はそれを空と同じに扱っていた ── 権限が無いのに「中身が無い」ように見えた。
 *
 * **1階層ずつ潜るダイアログ（旧 `FilePicker`）はここに畳んだ。**
 * 開く道が2つあると「どっちで開くんだっけ」が生まれる ── 案Cの「迷わない」はそこを削る話。
 */
class FileTreeView(
    context: Context,
    private val onPick: (File) -> Unit
) : ScrollView(context) {

    private class Node(val file: File, val depth: Int) {
        var expanded = false
        var children: List<Node>? = null

        /** 開いたが一覧が取れなかった。 */
        var unreadable = false
    }

    /** 場所1つ。ボリュームの根と、画面に出す名前。 */
    private class Place(val dir: File, val name: String)

    private var rootUnreadable = false

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var root: File = File("/sdcard")
    private var nodes: List<Node> = emptyList()
    private var palette: Palette = Palette.SUMI
    private var showHidden = false
    private var sort = EditorSettings.FileSort.NAME

    init {
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isFillViewport = true
    }

    fun apply(palette: Palette, settings: EditorSettings) {
        this.palette = palette
        showHidden = settings.showHiddenFiles
        sort = settings.fileSort
        setBackgroundColor(palette.background)
        rebuild()
    }

    /** 根を差し替える。開いているファイルの親を根にすると、**開いた続きから辿れる**。 */
    fun setRoot(dir: File) {
        root = if (dir.isDirectory) dir else dir.parentFile ?: File("/sdcard")
        val listed = childrenOf(root, 0)
        rootUnreadable = listed == null
        nodes = listed ?: emptyList()
        rebuild()
    }

    fun currentRoot(): File = root

    /** 子を並べる。**一覧が取れなければ null**（空のフォルダとは別物として扱う）。 */
    private fun childrenOf(dir: File, depth: Int): List<Node>? =
        dir.listFiles()
            ?.filter { showHidden || !it.isHidden }
            ?.sortedWith(order())
            ?.map { Node(it, depth) }

    /** **フォルダは常に上**。1階層ずつ潜る道具なので、名前順で散らばると辿れない。 */
    private fun order(): Comparator<File> {
        val folderFirst = compareBy<File> { !it.isDirectory }
        return when (sort) {
            EditorSettings.FileSort.NAME -> folderFirst.thenBy { it.name.lowercase() }
            EditorSettings.FileSort.MODIFIED ->
                folderFirst.thenByDescending { it.lastModified() }.thenBy { it.name.lowercase() }
            EditorSettings.FileSort.SIZE ->
                folderFirst.thenByDescending { it.length() }.thenBy { it.name.lowercase() }
        }
    }

    private fun rebuild() {
        list.removeAllViews()
        list.addView(header())
        for (place in places()) list.addView(placeRow(place))
        list.addView(View(context).apply { setBackgroundColor(palette.frame) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        if (rootUnreadable) list.addView(unreadableRow(0))
        for (node in flatten(nodes)) {
            list.addView(row(node))
            if (node.unreadable && node.expanded) list.addView(unreadableRow(node.depth + 1))
        }
    }

    /**
     * 場所の一覧。**マウントされていて根の道が分かるものだけ。**
     * 名前は Android が付けた説明（「内部共有ストレージ」「SD カード」など）に UUID を添える ──
     * 同じ種類のカードが2枚あっても見分けられるように。
     */
    private fun places(): List<Place> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val dir = volume.directory ?: return@mapNotNull null
            val name = if (volume.isPrimary) {
                "内部ストレージ"
            } else {
                listOfNotNull(volume.getDescription(context), volume.uuid).joinToString("  ")
            }
            Place(dir, name)
        }
    }

    private fun placeRow(place: Place): View {
        val here = root.absolutePath == place.dir.absolutePath ||
            root.absolutePath.startsWith(place.dir.absolutePath + "/")
        return TextView(context).apply {
            text = "▣ ${place.name}"
            textSize = 14f
            maxLines = 1
            setTextColor(if (here) palette.accent else palette.text)
            if (here) setTypeface(null, Typeface.BOLD)
            setPadding(PAD * 2, ROW_PAD, PAD * 2, ROW_PAD)
            isClickable = true
            contentDescription = "場所: ${place.name}"
            setOnClickListener { setRoot(place.dir) }
        }
    }

    /** 一覧が取れなかったことを言う行。**空に見せない**（`handoff.md` の「0 件は無いと見分けが付かない」）。 */
    private fun unreadableRow(depth: Int): View = TextView(context).apply {
        text = buildString {
            repeat(depth) { append("　") }
            append("　読めない（権限が無いか、一覧を許されていない場所）")
        }
        textSize = 13f
        setTextColor(palette.dim)
        setPadding(PAD * 2, ROW_PAD / 2, PAD * 2, ROW_PAD / 2)
    }

    /** 開いているものだけを上から順に並べる。**畳んだ枝の中は数えない**。 */
    private fun flatten(source: List<Node>): List<Node> {
        val out = ArrayList<Node>()
        for (node in source) {
            out.add(node)
            if (node.expanded) node.children?.let { out.addAll(flatten(it)) }
        }
        return out
    }

    private fun header(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PAD, PAD, PAD, PAD)
        }
        val up = TextView(context).apply {
            text = "↑"
            textSize = 16f
            setTextColor(palette.text)
            setPadding(PAD, PAD, PAD * 2, PAD)
            setOnClickListener { root.parentFile?.let { setRoot(it) } }
        }
        val path = TextView(context).apply {
            text = root.absolutePath
            textSize = 12f
            setTextColor(palette.dim)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        bar.addView(up)
        bar.addView(path, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun row(node: Node): View {
        val isDir = node.file.isDirectory
        return TextView(context).apply {
            text = buildString {
                repeat(node.depth) { append("　") }
                if (isDir) append(if (node.expanded) "▾ " else "▸ ") else append("　")
                append(node.file.name)
            }
            textSize = 14f
            setTextColor(if (isDir) palette.text else palette.dim)
            if (isDir) setTypeface(null, Typeface.BOLD)
            // **行全体を当たり判定にする。** 設定画面のトグルで踏んだのと同じ話で、
            // 指で触る画面では文字の幅しか反応しないと外す（E7/E8 の実機で見つけた②）。
            setPadding(PAD * 2, ROW_PAD, PAD * 2, ROW_PAD)
            isClickable = true
            setOnClickListener {
                if (isDir) toggle(node) else onPick(node.file)
            }
            // 深く潜るより根を移す方が速い場面があるので、長押しでそのフォルダを根にする。
            if (isDir) setOnLongClickListener { setRoot(node.file); true }
        }
    }

    private fun toggle(node: Node) {
        if (!node.expanded && node.children == null) {
            val listed = childrenOf(node.file, node.depth + 1)
            node.unreadable = listed == null
            node.children = listed ?: emptyList()
        }
        node.expanded = !node.expanded
        rebuild()
    }

    private companion object {
        const val PAD = 8
        const val ROW_PAD = 18
    }
}
