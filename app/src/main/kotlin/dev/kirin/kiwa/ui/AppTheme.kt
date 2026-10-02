package dev.kirin.kiwa.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowInsetsController
import dev.kirin.editoradapter.EditorTheme

/**
 * 選べる配色。**端末に合わせる** と、暗い4つ・明るい4つの色違い。
 *
 * 名前は「呼び名（Dark）／（Light）」で揃えてある。
 * 初期の「明るい」「生成り」「暗い」は同じ日に外した ── 古い設定ファイルに残っている名前は
 * [fromStored] が近い色違いへ読み替える。
 *
 * 既定を [SYSTEM] ではなく暗い配色（[SUMI]）にしてある ── タブレットが明るい設定のままだと
 * 開いた瞬間に真っ白で眩しい、というのが最初に出た不満だったので、
 * 「端末に合わせる」は選べるが既定にはしない。
 *
 * **設定ファイルには名前（`SUMI` など）で残る**ので、名前は変えない。足すのは自由。
 *
 * @param fixed 固定の配色。[SYSTEM] だけは端末の明暗で決まるので null
 */
enum class ThemeChoice(val label: String, internal val fixed: Palette?) {
    SYSTEM("端末に合わせる（Auto）", null),
    SUMI("Obsidian Gold（Dark）", Palette.SUMI),
    YORU("Midnight Frost（Dark）", Palette.YORU),
    MORI("Deep Forest（Dark）", Palette.MORI),
    BENI("Crimson Night（Dark）", Palette.BENI),
    KAMI("Ink & Paper（Light）", Palette.KAMI),
    SHIRO("Glacier（Light）", Palette.SHIRO),
    KINARI("Sage Linen（Light）", Palette.KINARI),
    KIRI("Lavender Mist（Light）", Palette.KIRI);

    fun resolve(context: Context): Palette = fixed ?: run {
        val night = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (night) Palette.SUMI else Palette.KINARI
    }

    companion object {
        /** 新しく入れたときの配色。 */
        val DEFAULT = SUMI

        /**
         * 外した配色の読み替え。**地の色がいちばん近いもの**へ寄せる ──
         * 灰の暗い地 → 墨、白 → 白と青、生成り → 生成りと緑。
         */
        private val RETIRED = mapOf("DARK" to SUMI, "LIGHT" to SHIRO, "CREAM" to KINARI)

        /**
         * 設定ファイルに書いてある名前から引く。外した名前は読み替え、
         * 知らない名前と空は null（呼び出し側が既定で埋める）。
         */
        fun fromStored(name: String?): ThemeChoice? {
            if (name.isNullOrEmpty()) return null
            return entries.firstOrNull { it.name == name } ?: RETIRED[name]
        }
    }
}

/**
 * アプリ側の色。**エディタの中の色はエンジンが持つ**ので、ここにあるのは
 * 地・バー・枠・強調の色と、システムバーの明暗だけ。
 *
 * [accent] / [onAccent] / [frame] は見た目案 01（2026-09-29）で足した ──
 * 領域を枠で囲み、今の領域と状態表示を強調の色で見せる形のため。
 */
enum class Palette(
    val background: Int,
    val toolbar: Int,
    val button: Int,
    val text: Int,
    val dim: Int,
    val editorTheme: EditorTheme,
    /** システムバーのアイコンを暗く描くか。**ここを間違えるとアイコンが地と同化して消える。** */
    val lightSystemBars: Boolean,
    /** 強調の色。今の領域の枠・見出し・状態表示の地・未保存の印に使う。 */
    val accent: Int,
    /** [accent] の地に載せる文字の色。 */
    val onAccent: Int,
    /** 領域の枠と区切り線の色。 */
    val frame: Int
) {
    // ---- 見た目案 01 の色違い8つ（2026-09-29）。数字は色の表と同じ ----
    // background = 地 / toolbar = バー / button = 選択の地 / text / dim = 控えめな文字 /
    // lightSystemBars / accent = 強調 / onAccent = 強調の地の文字 / frame = 枠。
    // 本文の中の色は engine-sora の assets/textmate/<名前>.json が持つ。

    SUMI(0xFF1C1C1C.toInt(), 0xFF252525.toInt(), 0xFF333333.toInt(), 0xFFD4D4D4.toInt(), 0xFF8A8A8A.toInt(), EditorTheme.SUMI, false, 0xFFD7BA7D.toInt(), 0xFF1C1C1C.toInt(), 0xFF5A5A5A.toInt()),
    YORU(0xFF1F242D.toInt(), 0xFF262C37.toInt(), 0xFF343B48.toInt(), 0xFFD8DEE9.toInt(), 0xFF8B95A7.toInt(), EditorTheme.YORU, false, 0xFF88C0D0.toInt(), 0xFF1F242D.toInt(), 0xFF4C566A.toInt()),
    MORI(0xFF1A201C.toInt(), 0xFF212923.toInt(), 0xFF2C3A30.toInt(), 0xFFD3DCD4.toInt(), 0xFF86958A.toInt(), EditorTheme.MORI, false, 0xFF8FCF8A.toInt(), 0xFF14261A.toInt(), 0xFF4A5A4E.toInt()),
    BENI(0xFF1D1A22.toInt(), 0xFF26222D.toInt(), 0xFF352F3E.toInt(), 0xFFE2DCE8.toInt(), 0xFF958CA1.toInt(), EditorTheme.BENI, false, 0xFFFF7A90.toInt(), 0xFF2A0F16.toInt(), 0xFF574E63.toInt()),
    KAMI(0xFFF7F5F0.toInt(), 0xFFECE8DF.toInt(), 0xFFE2DCCF.toInt(), 0xFF2A2723.toInt(), 0xFF6D665B.toInt(), EditorTheme.KAMI, true, 0xFF8A5D0C.toInt(), 0xFFFFFFFF.toInt(), 0xFFA79E8C.toInt()),
    SHIRO(0xFFFFFFFF.toInt(), 0xFFF3F5F8.toInt(), 0xFFDDE9F8.toInt(), 0xFF1F2328.toInt(), 0xFF5A6573.toInt(), EditorTheme.SHIRO, true, 0xFF0A62C9.toInt(), 0xFFFFFFFF.toInt(), 0xFF9AA6B4.toInt()),
    KINARI(0xFFF6F1E3.toInt(), 0xFFEDE6D3.toInt(), 0xFFE2D9BF.toInt(), 0xFF2D2A22.toInt(), 0xFF6A624F.toInt(), EditorTheme.KINARI, true, 0xFF3F7A3A.toInt(), 0xFFFFFFFF.toInt(), 0xFFA1967A.toInt()),
    KIRI(0xFFEEF0F4.toInt(), 0xFFE3E6EC.toInt(), 0xFFD9D4F0.toInt(), 0xFF22252B.toInt(), 0xFF5C6370.toInt(), EditorTheme.KIRI, true, 0xFF6B4FC4.toInt(), 0xFFFFFFFF.toInt(), 0xFF8F97A6.toInt());

    /**
     * システムバーのアイコンの明暗を合わせる。
     *
     * <b>入れないとナビゲーションバーの「戻る／ホーム」が見えなくなる。</b>
     * 端から端まで描く既定では、システムはバーを透明にしてアプリの地をそのまま見せるので、
     * 明るい地に明るいアイコンが載って同化する（実機で最初にそうなった）。
     */
    fun applySystemBars(activity: Activity) {
        val window = activity.window
        // 窓そのものの地も塗る。内容ビューの外側（システムバーの帯）は窓の地が透けるので、
        // ここを塗らないと**明るい配色にしてもナビゲーションバーの帯だけ暗いまま残る**。
        window.setBackgroundDrawable(ColorDrawable(background))

        // 端から端まで描く既定ではこの2つは無視される、というのが建前だが、
        // **この端末（HyperOS / Android 16）では効いている** ── 指定しないと親テーマの既定（黒）が
        // 残り、明るい配色にしてもナビゲーションバーの帯だけ暗いままになる。実測で確認した。
        @Suppress("DEPRECATION")
        window.statusBarColor = background
        @Suppress("DEPRECATION")
        window.navigationBarColor = background

        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val appearance = if (lightSystemBars) mask else 0
        // onCreate の時点では窓がまだ付いておらず、そのまま呼んでも効かないことがある。
        // 付いた後にもう一度出す ── 効かないと明るい地に明るいアイコンが載って読めなくなる。
        window.insetsController?.setSystemBarsAppearance(appearance, mask)
        window.decorView.post {
            window.insetsController?.setSystemBarsAppearance(appearance, mask)
        }
    }

    fun applyTo(root: View) {
        root.setBackgroundColor(background)
    }
}
