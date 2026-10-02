package dev.kirin.kiwa.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.text.TextUtils
import android.widget.TextView
import dev.kirin.kiwa.file.FolderChoice
import java.io.File

/**
 * 保存先のフォルダを選ぶ画面（2026-10-02）。新しいファイルと名前を付けて保存の「場所を選ぶ…」から開く。
 *
 * **1階層ずつ潜る一覧**で、開くたびに作り直す（`AlertDialog` の一覧は押すと閉じるので）。
 * 並びは「↑ 上のフォルダ」→「◆ 置き場（内部ストレージ・SD カード）」→「▸ 中のフォルダ」で固定。
 * 置き場は `/storage` を一覧して探すのではなく、**端末が知らせるもの**を出す
 * ── `/storage` は一覧を読めない（2026-09-29 の実機、U4 S4）。
 *
 * 中身の判定は [FolderChoice] が持つ。
 */
object FolderChooser {

    fun show(activity: Activity, start: File, showHidden: Boolean, onChosen: (File) -> Unit) {
        val places = FolderChoice.places(storagePlaces(activity))
        open(activity, start, places, showHidden, onChosen)
    }

    private fun open(
        activity: Activity,
        dir: File,
        places: List<FolderChoice.Place>,
        showHidden: Boolean,
        onChosen: (File) -> Unit
    ) {
        val listing = FolderChoice.list(dir, showHidden)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }
        val reopen = { target: File -> open(activity, target, places, showHidden, onChosen) }

        listing.parent?.let { parent -> item("↑ 上のフォルダ") { reopen(parent) } }
        for (place in places) {
            if (place.dir.absolutePath == dir.absolutePath) continue
            item("◆ ${place.label}") { reopen(place.dir) }
        }
        // **読めないのと空なのを分けて出す**（FolderChoice の冒頭）。押しても同じ場所へ戻るだけ。
        when {
            !listing.readable -> item("（このフォルダは中を読めない ── ↑ か ◆ から選んで）") { reopen(dir) }
            listing.folders.isEmpty() -> item("（中にフォルダは無い）") { reopen(dir) }
        }
        for (folder in listing.folders) item("▸ ${folder.name}") { reopen(folder) }

        // 題は道そのもの。**長い道は頭を削る** ── 見分けたいのは末尾の方。
        val title = TextView(activity).apply {
            text = dir.absolutePath
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            setPadding(PAD * 2, PAD * 2, PAD * 2, PAD)
        }
        AlertDialog.Builder(activity)
            .setCustomTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setPositiveButton("ここにする") { _, _ -> onChosen(dir) }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 端末が今つないでいる置き場。内部ストレージは必ず先頭に入れる（一覧が空でも戻れるように）。 */
    private fun storagePlaces(context: Context): List<FolderChoice.Place> {
        val internal = FolderChoice.Place("内部ストレージ", Environment.getExternalStorageDirectory())
        val manager = context.getSystemService(StorageManager::class.java) ?: return listOf(internal)
        val volumes = manager.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume -> volume.directory?.let { FolderChoice.Place(volume.getDescription(context), it) } }
        return listOf(internal) + volumes
    }

    private const val PAD = 24
}
