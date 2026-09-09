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

    private val URL_IN_TEXT_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val HOST_IN_TEXT_REGEX = Regex("""(?:www\.)?allureparfum\.ru\S*""", RegexOption.IGNORE_CASE)

    /** Символы, которые при вставке из мессенджера прилипают к концу ссылки. */
    private val TRAILING_NOISE = charArrayOf(',', '.', ')', '(', ';', ':', '!', '?', '"', '\'', '»', '«', '>')

    /**
     * Ссылка из того, что пользователь вставил в поле. Обычно это не чистый URL, а текст
     * шаринга: «Посмотри: https://allureparfum.ru/katalog/creed/aventus.html, цена 3000».
     * Без извлечения получается битый адрес, и товар не заводится вообще.
     */
    fun extractUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val candidate = URL_IN_TEXT_REGEX.find(trimmed)?.value
            ?: HOST_IN_TEXT_REGEX.find(trimmed)?.value
            ?: trimmed

        return candidate.trimEnd(*TRAILING_NOISE)
    }

    fun normalize(input: String): String? {
        val candidate = extractUrl(input) ?: return null

        return when {
            candidate.startsWith("http://", ignoreCase = true) ||
                candidate.startsWith("https://", ignoreCase = true) -> candidate
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("/") -> "$BASE$candidate"
            candidate.contains("allureparfum.ru", ignoreCase = true) -> "https://${candidate.removePrefix("//")}"
            else -> "$BASE/${candidate.removePrefix("/")}"
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
        val path = runCatching { URI(url).path }.getOrNull()?.trim('/') ?: return null
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
