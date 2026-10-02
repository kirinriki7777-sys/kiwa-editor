package dev.kirin.kiwa.file

/**
 * ファイル名から構文ハイライトの言語名を決める。
 *
 * **ここが持つのは「拡張子 → 名前」だけ。** 名前を文法に直すのはエンジンの仕事で、
 * この層はエンジンがその言語を持っているかを知らない
 * （[dev.kirin.editoradapter.EditorEngine.setLanguage] の約束）。知らない名前を渡しても
 * 色が付かないだけなので、対応表を先に増やしても壊れない。
 *
 * **無い言語をそれらしい別の文法で代用しない。** `.json5` を JSON として塗るような当て方は、
 * ほとんどの行が合っているぶん**間違っている行に気づけない**。
 * 対応表を育てるのは設定画面（E8）の「拡張子 → 文法の対応」の仕事。
 */
object Languages {

    /** 拡張子（小文字）→ 言語名。 */
    private val byExtension = mapOf(
        // JVM
        "kt" to "kotlin",
        "kts" to "kotlin",
        "java" to "java",
        "gradle" to "groovy",
        "groovy" to "groovy",
        // スクリプト
        "py" to "python",
        "pyi" to "python",
        "pyw" to "python",
        "lua" to "lua",
        "sh" to "shellscript",
        "bash" to "shellscript",
        "zsh" to "shellscript",
        // web
        "js" to "javascript",
        "mjs" to "javascript",
        "cjs" to "javascript",
        "jsx" to "javascript",
        "ts" to "typescript",
        "mts" to "typescript",
        "cts" to "typescript",
        "tsx" to "typescript",
        "html" to "html",
        "htm" to "html",
        "css" to "css",
        // 設定と文書
        "json" to "json",
        "yaml" to "yaml",
        "yml" to "yaml",
        "xml" to "xml",
        "xsd" to "xml",
        "xsl" to "xml",
        "svg" to "xml",
        "md" to "markdown",
        "markdown" to "markdown",
        // ネイティブ
        "c" to "c",
        "h" to "c",
        "cpp" to "cpp",
        "cc" to "cpp",
        "cxx" to "cpp",
        "hpp" to "cpp",
        "hh" to "cpp",
        "hxx" to "cpp",
        "rs" to "rust",
        "go" to "go"
    )

    /**
     * ファイル名そのもの（小文字）→ 言語名。
     *
     * **拡張子を持たない設定ファイルのため。** `.bashrc` は「拡張子 bashrc」ではないので、
     * 拡張子の表だけでは引けない。
     */
    private val byFileName = mapOf(
        ".bashrc" to "shellscript",
        ".bash_profile" to "shellscript",
        ".bash_aliases" to "shellscript",
        ".zshrc" to "shellscript",
        ".profile" to "shellscript"
    )

    /** 対応表に無ければ null（＝色を付けない）。 */
    fun of(fileName: String): String? {
        val lower = fileName.lowercase()
        byFileName[lower]?.let { return it }
        val dot = lower.lastIndexOf('.')
        // 先頭のドットは拡張子の区切りではない（`.bashrc` は上の表で引く）。
        if (dot <= 0 || dot == lower.length - 1) return null
        return byExtension[lower.substring(dot + 1)]
    }

    /**
     * この対応表が返しうる言語名の全部。**件数でなく集合で照合する側**が引くための口
     * （[dev.kirin.kiwa.edit.LineComments] の対応表と食い違っていないかを試験で見る）。
     */
    fun allLanguageNames(): Set<String> = (byExtension.values + byFileName.values).toSet()
}
