package dev.kirin.kiwa.trace

/**
 * 記録1行の組み立て。**Android に依存しない**ので JVM の単体テストで形式を押さえられる。
 *
 * `android.jar` の `org.json.JSONObject` は JVM 単体テストで実装として使えないため、JSON を自前で組み立てる。
 *
 * 固定フィールドは `seq` / `sessionId` / `testId` / `wallTimeMs` / `elapsedNanos` /
 * `thread` / `source` / `event` の順に並び、その後に呼び出し側のキーと値が続く。
 * **順番と名前を保ち、計測ログの形式を安定させる。**
 */
object JsonlFormat {

    fun line(
        seq: Long,
        sessionId: String,
        testId: String,
        wallTimeMs: Long,
        elapsedNanos: Long,
        thread: String,
        source: String,
        event: String,
        fields: Array<out Any?>
    ): String {
        val sb = StringBuilder(128)
        sb.append('{')
        appendPair(sb, "seq", seq, first = true)
        appendPair(sb, "sessionId", sessionId)
        appendPair(sb, "testId", testId)
        appendPair(sb, "wallTimeMs", wallTimeMs)
        appendPair(sb, "elapsedNanos", elapsedNanos)
        appendPair(sb, "thread", thread)
        appendPair(sb, "source", source)
        appendPair(sb, "event", event)
        var i = 0
        while (i + 1 < fields.size) {
            val key = fields[i]?.toString() ?: "null"
            appendPair(sb, key, fields[i + 1])
            i += 2
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendPair(sb: StringBuilder, key: String, value: Any?, first: Boolean = false) {
        if (!first) sb.append(',')
        appendString(sb, key)
        sb.append(':')
        appendValue(sb, value)
    }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            // NaN と Infinity は JSON に無いので文字列へ落とす。
            is Double -> if (value.isFinite()) sb.append(value) else appendString(sb, value.toString())
            is Float -> if (value.isFinite()) sb.append(value) else appendString(sb, value.toString())
            is Number -> sb.append(value.toString())
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                // 制御文字をそのまま出すと JSON として壊れる。IME は素の U+0001 等も送ってくる。
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
