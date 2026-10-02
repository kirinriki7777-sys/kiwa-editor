package dev.kirin.kiwa.edit

/**
 * 言語名 → 行コメントの記号。[dev.kirin.kiwa.file.Languages] の言語名 18 件と1対1
 * （`tools/check.sh` が [dev.kirin.kiwa.file.Languages.allLanguageNames] との**集合の一致**を見る）。
 *
 * **「無し」は表から落とさず null として持つ。** 落とすと「行コメントが無い言語」と
 * 「表に足し忘れた言語」が区別できなくなる（`Commands.kt` が `planned` を表に載せているのと同じ理由）。
 */
object LineComments {

    private val byLanguage: Map<String, String?> = mapOf(
        "kotlin" to "//",
        "java" to "//",
        "groovy" to "//",
        "javascript" to "//",
        "typescript" to "//",
        "c" to "//",
        "cpp" to "//",
        "rust" to "//",
        "go" to "//",
        "python" to "#",
        "shellscript" to "#",
        "yaml" to "#",
        "lua" to "--",
        "html" to null,
        "css" to null,
        "json" to null,
        "xml" to null,
        "markdown" to null
    )

    /** 知らない言語名と null は null（＝色が付かないファイルではコメントもできない）。 */
    fun of(language: String?): String? {
        language ?: return null
        return byLanguage[language]
    }

    /** この対応表が持つ言語名の集合。試験が [dev.kirin.kiwa.file.Languages] と突き合わせる。 */
    fun languages(): Set<String> = byLanguage.keys
}
