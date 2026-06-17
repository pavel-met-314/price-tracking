package com.example.otsled.data.parser

import java.net.URI
import java.util.Locale

object ProductUrlNormalizer {
    private const val BASE = "https://allureparfum.ru"

    private val IGNORED_PATH_SEGMENTS = setOf(
        "katalog",
        "catalog",
        "brend",
        "brendy",
        "brand",
        "brands",
        "zhenskaya-parfyumeriya",
        "muzhskaya-parfyumeriya",
        "uniseks",
    )

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

    fun inferTitleFromUrl(input: String): String? {
        val url = normalize(input) ?: return null
        val path = URI(url).path?.trim('/') ?: return null
        val segments = path
            .removeSuffix(".html")
            .removeSuffix(".htm")
            .split('/')
            .filter { it.isNotBlank() && it !in IGNORED_PATH_SEGMENTS }

        if (segments.isEmpty()) return null

        return segments
            .takeLast(2)
            .joinToString(" ") { formatSlug(it) }
            .takeIf { it.isNotBlank() }
    }

    private fun formatSlug(slug: String): String {
        return slug
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase(Locale("ru")).replaceFirstChar { char ->
                    char.titlecase(Locale("ru"))
                }
            }
    }
}
