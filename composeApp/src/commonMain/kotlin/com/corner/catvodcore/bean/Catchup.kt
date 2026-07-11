package com.corner.catvodcore.bean

import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class Catchup(
    val type: String = "",
    val source: String = "",
    val replace: String = "",
    val regex: String = "",
    val days: String = "",
) {
    fun isEmpty(): Boolean = source.isBlank()

    fun match(url: String): Boolean {
        if (regex.isBlank()) return false
        return url.contains(regex) || Regex(regex).containsMatchIn(url)
    }

    fun format(url: String, data: EpgData): String {
        var result = source
        val tokenPattern = Regex("""(\$?\{[^}]*\})""")
        tokenPattern.findAll(result).forEach { match ->
            result = result.replace(match.value, formatToken(match.value, data.startTime, data.endTime))
        }
        return if (type == "default") result else append(url, result)
    }

    private fun append(url: String, suffix: String): String {
        val splits = replace.split(",", limit = 2)
        var output = if (splits.size == 2) url.replace(splits[0], splits[1]) else url
        val hasQuery = runCatching { URI.create(output).query != null }.getOrDefault(output.contains("?"))
        output += if (hasQuery) suffix.replace("?", "&") else suffix
        return output
    }

    private fun formatToken(group: String, start: Long, end: Long): String {
        val tag = Regex("""\{([^}]+)\}""").find(group)?.groupValues?.getOrNull(1) ?: return ""
        val paren = tag.indexOf(')')
        return when {
            tag.startsWith("(b") && paren >= 0 -> formatTime(start, tag.substring(paren + 1))
            tag.startsWith("(e") && paren >= 0 -> formatTime(end, tag.substring(paren + 1))
            tag.startsWith("utcend:") -> (end / 1000).toString()
            tag.startsWith("utc:") -> (start / 1000).toString()
            else -> ""
        }
    }

    private fun formatTime(millis: Long, fmt: String): String {
        if (fmt == "timestamp") return (millis / 1000).toString()
        return DateTimeFormatter.ofPattern(fmt, Locale.getDefault())
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }

    companion object {
        fun pltv(): Catchup = Catchup(
            type = "append",
            days = "7",
            regex = "/PLTV/",
            replace = "/PLTV/,/TVOD/",
            source = "?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}",
        )

        fun decide(primary: Catchup, fallback: Catchup): Catchup {
            return when {
                !primary.isEmpty() -> primary
                !fallback.isEmpty() -> fallback
                else -> Catchup()
            }
        }
    }
}
