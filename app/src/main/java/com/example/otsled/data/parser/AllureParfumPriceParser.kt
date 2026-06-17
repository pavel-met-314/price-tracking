package com.example.otsled.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class AllureParfumPriceParser(
    private val client: OkHttpClient = defaultClient(),
) {
    fun isSupportedUrl(url: String): Boolean = ProductUrlNormalizer.isSupportedUrl(url)

    suspend fun fetchAndParse(rawUrl: String): ParseResult = withContext(Dispatchers.IO) {
        val url = ProductUrlNormalizer.normalize(rawUrl)
            ?: return@withContext ParseResult.Error("Укажите ссылку на allureparfum.ru")

        if (!ProductUrlNormalizer.isSupportedUrl(url)) {
            return@withContext ParseResult.Error("Поддерживаются только ссылки allureparfum.ru")
        }

        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching ParseResult.Error("HTTP ${response.code}")
                }
                val html = response.body?.string().orEmpty()
                if (html.contains("js-challenge-script") || html.contains("jsch._jsChallenge")) {
                    return@runCatching ParseResult.Error("Требуется WebView: сайт использует JS-защиту")
                }
                parseHtml(html, url)
            }
        }.getOrElse { error ->
            ParseResult.Error(error.message ?: "Ошибка сети")
        }
    }

    fun parseHtml(html: String, url: String = ""): ParseResult {
        return parseHtmlOrNull(html, url)
            ?: ParseResult.Error("Не удалось определить название товара")
    }

    fun parseHtmlOrNull(html: String, url: String = ""): ParseResult.Success? {
        val document = Jsoup.parse(html, url)

        val title = extractTitle(document)
            ?: ProductUrlNormalizer.inferTitleFromUrl(url)
            ?: return null

        val variants = extractVariants(document, html)
        if (variants.isEmpty()) {
            return null
        }

        return ParseResult.Success(title = title, variants = variants)
    }

    fun extractTitleFromHtml(html: String, url: String = ""): String? {
        return extractTitle(Jsoup.parse(html, url))
            ?: ProductUrlNormalizer.inferTitleFromUrl(url)
    }

    private fun extractTitle(document: Document): String? {
        return document.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?: document.select("h1").first()?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.select("[itemprop=name]").first()?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" - ").trim().takeIf { it.isNotBlank() }
    }

    private fun extractVariants(document: Document, html: String): List<ParsedProductVariant> {
        val strategies = listOf(
            { extractFromVolumeAnchors(document) },
            { extractFromOfferRows(document) },
            { extractFromTextBlocks(document.text()) },
            { extractFromEmbeddedJson(html) },
        )

        for (strategy in strategies) {
            val variants = strategy().sortedBy { parseVolumeMl(it.volume) }
            if (variants.size > 1) return variants
        }

        val single = strategies.firstNotNullOfOrNull { strategy ->
            strategy().singleOrNull()
        } ?: extractSinglePrice(document)?.let { price ->
            ParsedProductVariant(volume = "1 мл", price = price)
        }

        return single?.let { listOf(it) } ?: emptyList()
    }

    private fun extractFromVolumeAnchors(document: Document): List<ParsedProductVariant> {
        val variants = linkedMapOf<String, ParsedProductVariant>()

        document.allElements.forEach { element ->
            val ownText = element.ownText().trim()
            if (!VOLUME_ONLY_REGEX.matches(ownText)) return@forEach

            val container = findOfferContainer(element) ?: return@forEach
            parseOfferContainer(container)?.let { variant ->
                variants[variant.variantKey()] = variant
            }
        }

        return variants.values.toList()
    }

    private fun extractFromOfferRows(document: Document): List<ParsedProductVariant> {
        val variants = linkedMapOf<String, ParsedProductVariant>()

        val rowSelectors = listOf(
            "tbody tr",
            "tr",
            ".product-offers-item",
            ".product-item-detail-offers-item",
            "[data-entity=sku-line-block]",
            ".catalog-block-offers-item",
            ".offers_list > *",
            "[class*=offer]",
            "[class*=trade]",
        )

        for (selector in rowSelectors) {
            document.select(selector).forEach { row ->
                parseOfferContainer(row)?.let { variant ->
                    variants[variant.variantKey()] = variant
                }
            }
            if (variants.size > 1) break
        }

        return variants.values.toList()
    }

    private fun extractFromTextBlocks(text: String): List<ParsedProductVariant> {
        val normalized = text.replace('\u00A0', ' ')
        val variants = linkedMapOf<String, ParsedProductVariant>()

        OFFER_BLOCK_REGEX.findAll(normalized).forEach { match ->
            val volume = normalizeVolume(match.groupValues[2])
            val price = PriceNormalizer.normalize("${match.groupValues[3]} руб") ?: return@forEach
            val article = match.groupValues[1].ifBlank { null }
            val label = extractLabel(match.value)
            val variant = ParsedProductVariant(
                volume = volume,
                label = label,
                article = article,
                price = price,
            )
            variants[variant.variantKey()] = variant
        }

        return variants.values.toList()
    }

    private fun extractFromEmbeddedJson(html: String): List<ParsedProductVariant> {
        val variants = linkedMapOf<String, ParsedProductVariant>()

        JSON_OFFER_REGEX.findAll(html).forEach { match ->
            val volumeRaw = match.groupValues[1]
            if (!VOLUME_REGEX.containsMatchIn(volumeRaw)) return@forEach
            val volume = normalizeVolume(volumeRaw)
            val price = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return@forEach
            val article = match.groupValues[3].ifBlank { null }
            val variant = ParsedProductVariant(volume = volume, article = article, price = price)
            variants[variant.variantKey()] = variant
        }

        return variants.values.toList()
    }

    private fun findOfferContainer(element: Element): Element? {
        element.closest("tr")?.let { tr ->
            val text = tr.text()
            if (text.length in 8..900 && PRICE_IN_TEXT_REGEX.containsMatchIn(text)) {
                return tr
            }
        }
        element.closest("[class*=offer], [class*=trade], [class*=sku]")?.let { offer ->
            val text = offer.text()
            if (text.length in 8..900 && PRICE_IN_TEXT_REGEX.containsMatchIn(text)) {
                return offer
            }
        }
        return element.parents()
            .filter { parent ->
                val text = parent.text()
                text.length in 8..900 &&
                    VOLUME_REGEX.containsMatchIn(text) &&
                    PRICE_IN_TEXT_REGEX.containsMatchIn(text)
            }
            .minByOrNull { it.text().length }
            ?: element.parent()
    }

    private fun parseOfferContainer(container: Element): ParsedProductVariant? {
        val rowText = container.text().replace('\u00A0', ' ')
        if (!VOLUME_REGEX.containsMatchIn(rowText)) return null
        if (!PRICE_IN_TEXT_REGEX.containsMatchIn(rowText)) return null
        if (rowText.length > 900) return null

        val volumeMatch = VOLUME_REGEX.find(rowText) ?: return null
        val volume = normalizeVolume(volumeMatch.value)
        val article = ARTICLE_REGEX.find(rowText)?.groupValues?.get(1)
        val label = extractLabel(rowText)
        val prices = extractPricesFromRow(container, rowText)
        val price = prices.firstOrNull() ?: return null
        val oldPrice = prices.getOrNull(1)

        return ParsedProductVariant(
            volume = volume,
            label = label,
            article = article,
            price = price,
            oldPrice = oldPrice,
        )
    }

    private fun extractPricesFromRow(row: Element, rowText: String): List<Double> {
        val priceSelectors = listOf(
            ".product-item-detail-price-current",
            ".price_value",
            "[class*=price-current]",
            ".current-price",
            "[data-entity=price]",
            "[class*=cost]",
        )

        val fromSelectors = priceSelectors.flatMap { selector ->
            row.select(selector).mapNotNull { PriceNormalizer.normalize(it.text()) }
        }
        if (fromSelectors.isNotEmpty()) {
            val current = fromSelectors.firstOrNull { PriceNormalizer.isReasonablePrice(it) }
                ?: return PriceNormalizer.extractPricesFromOfferText(rowText)
            val oldPrices = row.select(".product-item-detail-price-old, [class*=price-old]")
                .mapNotNull { PriceNormalizer.normalize(it.text()) }
                .filter { PriceNormalizer.isReasonablePrice(it) }
            return listOf(current) + oldPrices.take(1)
        }

        return PriceNormalizer.extractPricesFromOfferText(rowText)
    }

    private fun extractLabel(rowText: String): String {
        return if (rowText.contains("уценка", ignoreCase = true)) "уценка" else ""
    }

    private fun normalizeVolume(raw: String): String {
        val digits = Regex("""(\d+)""").find(raw)?.groupValues?.get(1) ?: raw
        return "$digits мл"
    }

    private fun parseVolumeMl(volume: String): Int {
        return Regex("""(\d+)""").find(volume)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun extractSinglePrice(document: Document): Double? {
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
            "#price_value",
            ".bx_price",
            "[data-entity=price]",
            ".catalog-block-price",
        )
        for (selector in selectors) {
            document.select(selector).forEach { element ->
                PriceNormalizer.normalize(element.text())?.let { return it }
            }
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
        private val VOLUME_REGEX = Regex("""(\d+)\s*мл\.?""", RegexOption.IGNORE_CASE)
        private val VOLUME_ONLY_REGEX = Regex("""(\d+)\s*мл\.?""", RegexOption.IGNORE_CASE)
        private val ARTICLE_REGEX = Regex("""Артикул\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val PRICE_IN_TEXT_REGEX = Regex("""(\d[\d\s\u00A0]*)\s*руб\.?""", RegexOption.IGNORE_CASE)
        private val OFFER_BLOCK_REGEX = Regex(
            """Артикул\s*(\d+)[\s\S]{0,120}?(\d+\s*мл\.?)[\s\S]{0,120}?(\d[\d\s\u00A0]+)\s*руб""",
            RegexOption.IGNORE_CASE,
        )
        private val JSON_OFFER_REGEX = Regex(
            """"(?:NAME|name|TITLE|title)"\s*:\s*"(?<volume>\d+\s*мл\.?)"[\s\S]{0,250}?"PRICE"\s*:\s*"(?<price>[\d.]+)"[\s\S]{0,250}?"art"[\s\S]{0,80}?"VALUE"\s*:\s*"(?<article>\d+)"""",
            RegexOption.IGNORE_CASE,
        )

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
