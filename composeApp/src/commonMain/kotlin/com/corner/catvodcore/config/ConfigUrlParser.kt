package com.corner.catvodcore.config

object ConfigUrlParser {
    fun parse(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return emptyList()

        val hasSeparator = trimmed.contains('\n') || trimmed.contains(',') || trimmed.contains(';')
        if (!hasSeparator) {
            return listOf(trimmed).filter { isUrl(it) }
        }

        return trimmed
            .split('\n', ',', ';')
            .flatMap { line -> line.split(' ') }
            .map { it.trim() }
            .filter { isUrl(it) }
            .distinct()
    }

    private fun isUrl(value: String): Boolean {
        return value.startsWith("http", ignoreCase = true) || value.startsWith("file", ignoreCase = true)
    }
}
