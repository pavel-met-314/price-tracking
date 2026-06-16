package com.example.otsled.data.parser

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.TimeUnit

class AllureParfumPriceParser(
    private val client: OkHttpClient = defaultClient(),
) {
    fun isSupportedUrl(url: String): Boolean {
        return runCatching {
            val host = URI(url).host?.lowercase().orEmpty()
            host == "allureparfum.ru" || host.endsWith(".allureparfum.ru")
        }.getOrDefault(false)
    }

    suspend fun fetchAndParse(url: String): ParseResult {
        if (!isSupportedUrl(url)) {
            return ParseResult.Error("Поддерживаются только ссылки allureparfum.ru")
        }

        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ParseResult.Error("HTTP ${response.code}")
                }
                val html = response.body?.string().orEmpty()
                if (html.contains("js-challenge-script") || html.contains("jsch._jsChallenge")) {
                    return ParseResult.Error("Требуется WebView: сайт использует JS-защиту")
                }
                parseHtml(html, url)
            }
        }.getOrElse { error ->
            ParseResult.Error(error.message ?: "Ошибка сети")
        }
    }

    fun parseHtml(html: String, url: String = ""): ParseResult {
        val document = Jsoup.parse(html, url)

        val title = extractTitle(document)
            ?: return ParseResult.Error("Не удалось определить название товара")

        val price = extractPrice(document)
            ?: return ParseResult.Error("Не удалось определить цену товара")

        return ParseResult.Success(title = title, price = price)
    }

    private fun extractTitle(document: Document): String? {
        return document.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?: document.select("h1").first()?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.select("[itemprop=name]").first()?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" - ").trim().takeIf { it.isNotBlank() }
    }

    private fun extractPrice(document: Document): Double? {
        extractFromJsonLd(document)?.let { return it }

        document.select("[itemprop=price]").forEach { element ->
            element.attr("content").takeIf { it.isNotBlank() }?.toDoubleOrNull()?.let { return it }
            PriceNormalizer.normalize(element.text())?.let { return it }
        }

        val selectors = listOf(
            ".product-item-detail-price-current",
            ".product-item-price-current",
            ".price_value",
            ".product-price",
            ".current-price",
            ".detail-price",
            "[data-entity=price]",
        )
        for (selector in selectors) {
            document.select(selector).forEach { element ->
                PriceNormalizer.normalize(element.text())?.let { return it }
            }
        }

        document.select("[class*=price], [id*=price]").forEach { element ->
            PriceNormalizer.normalize(element.text())?.let { return it }
        }

        return null
    }

    private fun extractFromJsonLd(document: Document): Double? {
        for (script in document.select("script[type=application/ld+json]")) {
            val json = script.data()
            if (!json.contains("price", ignoreCase = true)) continue
            val match = Regex(""""price"\s*:\s*"?(?<value>[\d.,]+)""").find(json)
            val value = match?.groups?.get("value")?.value ?: continue
            val normalized = value.replace(',', '.').toDoubleOrNull() ?: continue
            return normalized
        }
        return null
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
