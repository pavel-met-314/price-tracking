package com.example.otsled.data.parser

import java.net.URI

object ProductUrlNormalizer {
    private const val BASE = "https://allureparfum.ru"

    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$BASE$trimmed"
            trimmed.contains("allureparfum.ru", ignoreCase = true) -> {
                if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
            }
            else -> "$BASE/${trimmed.removePrefix("/")}"
        }
    }

    fun isSupportedUrl(input: String): Boolean {
        val url = normalize(input) ?: return false
        return runCatching {
            val host = URI(url).host?.lowercase().orEmpty()
            host == "allureparfum.ru" || host.endsWith(".allureparfum.ru")
        }.getOrDefault(false)
    }
}
