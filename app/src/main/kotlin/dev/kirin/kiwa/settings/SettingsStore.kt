package dev.kirin.kiwa.settings

import android.content.Context
import android.util.Log
import dev.kirin.kiwa.ui.ThemeChoice
import java.io.File
import org.json.JSONObject

/**
 * 設定の保存先。**JSON 1ファイル**（`filesDir/settings.json`）。
 *
 * ## 状態と設定を分ける
 *
 * ここに入るのは**利用者が設定した値**だけ。「最後に開いたディレクトリ」のような
 * アプリが勝手に覚えた値は [SharedPreferences][android.content.SharedPreferences] のまま残す ──
 * 混ぜると、設定ファイルを母艦から書き換えたときにアプリの内部状態まで巻き戻る。
 *
 * ## 読めなかったら既定で始める
 *
 * 設定ファイルが壊れていても**アプリは開く**。壊れたファイルは消さずに残す
 * （`settings.json.broken` へ退避）── 手で直した方が早いことがあるので、
 * 黙って上書きして中身を失わせない。
 */
class SettingsStore(context: Context) {

    private val context = context.applicationContext
    private val file = File(this.context.filesDir, FILE_NAME)

    fun load(): EditorSettings {
        if (!file.exists()) return migrateFromPreferences()
        val text = runCatching { file.readText() }.getOrElse {
            Log.w(TAG, "failed to read $FILE_NAME", it)
            return EditorSettings()
        }
        return runCatching { EditorSettings.fromJson(JSONObject(text)) }.getOrElse {
            Log.w(TAG, "failed to parse $FILE_NAME", it)
            // 壊れた設定を黙って捨てない。次の save で上書きされる前に脇へ退ける。
            runCatching { file.copyTo(File(file.parentFile, "$FILE_NAME.broken"), overwrite = true) }
            EditorSettings()
        }
    }

    /** 一時ファイル経由で差し替える。途中で落ちても半分書けた設定が残らないように。 */
    fun save(settings: EditorSettings) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        runCatching {
            temp.writeText(settings.toJson().toString(2))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "failed to write $FILE_NAME", it) }
    }

    /**
     * `SharedPreferences` に直に書いていた頃の値を1回だけ引き継ぐ。
     *
     * 引き継ぐのは配色だけ ── それ以外はまだ設定として存在しなかった。
     * 折り返しは**保存していなかった**（起動のたびに切れていた）ので、既定のまま。
     */
    private fun migrateFromPreferences(): EditorSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(LEGACY_KEY_THEME, null) ?: return EditorSettings()
        val theme = ThemeChoice.fromStored(saved) ?: return EditorSettings()
        val settings = EditorSettings(theme = theme)
        save(settings)
        prefs.edit().remove(LEGACY_KEY_THEME).apply()
        return settings
    }

    private companion object {
        const val TAG = "KiwaSettings"
        const val FILE_NAME = "settings.json"
        const val PREFS = "kiwa"
        const val LEGACY_KEY_THEME = "theme"
    }
}
