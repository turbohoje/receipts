package cc.rocketscience.receipts.export

/** RFC 4180 CSV. Quotes only where required, which keeps the output readable. */
object Csv {

    private const val CRLF = "\r\n"

    fun field(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value != value.trim()
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun row(vararg values: String): String = values.joinToString(",") { field(it) } + CRLF

    fun row(values: List<String>): String = values.joinToString(",") { field(it) } + CRLF
}
