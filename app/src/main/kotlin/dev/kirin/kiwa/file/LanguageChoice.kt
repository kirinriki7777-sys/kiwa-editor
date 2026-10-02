package dev.kirin.kiwa.file

/**
 * タブの言語を手で選んだか（U3）。**画面を知らない**ので単体で試験できる。
 *
 * ## 持つのはタブの中だけ
 *
 * 選んだ言語は**そのタブを開いている間だけ**効く。
 * 設定にもファイルにも書かない ── 拡張子ごとにずっと変えたいなら、設定の「拡張子 → 言語」がその道。
 *
 * ## 自動に戻せる
 *
 * [Auto] は「ファイル名から決める」で、手で選ぶ前の状態そのもの。
 * 手で選んだ後でも [Auto] を選び直せば、名前から引いた言語に戻る。
 */
sealed class LanguageChoice {

    /** ファイル名から決める（手で選んでいない）。 */
    object Auto : LanguageChoice()

    /** 手で選んだ。[language] が null なら「色を付けない」を選んだ。 */
    data class Fixed(val language: String?) : LanguageChoice()

    /** 実際に使う言語。[fromName] はファイル名から引いた言語（無題なら null）。 */
    fun resolve(fromName: String?): String? = when (this) {
        Auto -> fromName
        is Fixed -> language
    }

    /** 選択肢1つ。[selected] は今選ばれているもの。 */
    data class Option(val label: String, val choice: LanguageChoice, val selected: Boolean)

    companion object {
        const val PLAIN_LABEL = "色を付けない"

        /**
         * 並べる選択肢。**先頭は自動**、次に「色を付けない」、その後に言語名を名前順。
         *
         * 言語名は組み込みの表（[Languages.allLanguageNames]）と、設定で足した拡張子の行き先を合わせたもの。
         * 同梱の文法と組み込みの表が食い違っていないことは `tools/check-languages.sh` が見ている。
         *
         * @param fromName ファイル名から引いた言語。自動の行に「今なら何になるか」を出すために使う
         * @param current  今のタブの選択
         * @param extra    設定の「拡張子 → 言語」で足された言語名
         */
        fun options(fromName: String?, current: LanguageChoice, extra: Collection<String>): List<Option> {
            val names = (Languages.allLanguageNames() + extra.map { it.lowercase() })
                .filter { it.isNotBlank() }
                .toSortedSet()
            val out = ArrayList<Option>()
            out.add(Option("自動（ファイル名から: ${fromName ?: PLAIN_LABEL}）", Auto, current == Auto))
            out.add(Option(PLAIN_LABEL, Fixed(null), current == Fixed(null)))
            for (name in names) out.add(Option(name, Fixed(name), current == Fixed(name)))
            return out
        }

        /** 状態表示とパレットに出す短い名前。手で選んだものは印を付ける（自動と見分けるため）。 */
        fun label(fromName: String?, current: LanguageChoice): String {
            val name = current.resolve(fromName) ?: PLAIN_LABEL
            return if (current is Fixed) "$name（手動）" else name
        }
    }
}
