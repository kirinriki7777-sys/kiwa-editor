package dev.kirin.kiwa.file

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * ファイルへ届くための権限。
 *
 * **`MANAGE_EXTERNAL_STORAGE` ＋ 素の `File`** を採る。理由は3つ:
 *
 * 1. 置き場は母艦と Syncthing で共有するフォルダ。
 *    `/sdcard/Android/data/<pkg>/files/` は Android 11 以降ハードブロックで、
 *    そもそも同期元から書けない（ランチャー案件で実測済み）ので最初から候補外
 * 2. コードエディタはディレクトリを歩く。SAF の URI 配管はツリーを扱うには重い
 * 3. 母艦とファイルを突き合わせる（`diff` を 0 にする）のに、パスがそのまま見えている方が確かめやすい
 *
 * 個人の端末へ sideload する前提なので、配布ストアの制限は関係しない。
 */
object StorageAccess {

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * 権限が無いときの案内。**黙って何も起きないのが一番困る**ので、
     * 何が要るかを出してから設定画面へ送る。
     */
    fun promptFor(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("ファイルへのアクセスが要る")
            .setMessage(
                "Kiwa は端末のフォルダを直接読み書きする。\n" +
                    "設定画面が開くので「すべてのファイルへのアクセス」を許可して戻ってきて。"
            )
            .setPositiveButton("設定を開く") { _, _ ->
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            .setNegativeButton("やめる", null)
            .show()
    }
}
