package com.example.otsled.data.parser

class PricePageLoader(
    private val parser: AllureParfumPriceParser,
    private val webViewFetcher: WebViewPriceFetcher,
) {
    suspend fun fetchAndParse(rawUrl: String): ParseResult {
        val url = ProductUrlNormalizer.normalize(rawUrl)
            ?: return ParseResult.Error("Укажите ссылку на allureparfum.ru")

        if (!ProductUrlNormalizer.isSupportedUrl(url)) {
            return ParseResult.Error("Поддерживаются только ссылки allureparfum.ru")
        }

        val httpResult = parser.fetchAndParse(url)
        if (httpResult is ParseResult.Success && httpResult.variants.size > 1) {
            return httpResult
        }

        val content = webViewFetcher.fetchContent(url)
            ?: return ParseResult.Error(
                (httpResult as? ParseResult.Error)?.message ?: "Не удалось загрузить страницу",
            )

        val variants = when {
            content.variants.size > 1 -> content.variants
            content.html != null -> {
                val parsed = parser.parseHtmlOrNull(content.html, url)
                when {
                    parsed != null && parsed.variants.size > content.variants.size -> parsed.variants
                    content.variants.isNotEmpty() -> content.variants
                    parsed != null -> parsed.variants
                    else -> emptyList()
                }
            }
            else -> content.variants
        }

        if (variants.isEmpty()) {
            return ParseResult.Error("Не удалось определить цены по объёмам")
        }

        val resolvedTitle = resolveTitle(rawUrl, url, content)
            ?: return ParseResult.Error("Не удалось определить название товара")

        return ParseResult.Success(title = resolvedTitle, variants = variants)
    }

    private fun resolveTitle(
        rawUrl: String,
        url: String,
        content: WebPageContent,
    ): String? {
        content.title?.takeIf { it.isNotBlank() }?.let { return it }

        content.html?.let { html ->
            parser.extractTitleFromHtml(html, url)?.let { return it }
            parser.parseHtmlOrNull(html, url)?.title?.let { return it }
        }

        ProductUrlNormalizer.inferTitleFromUrl(rawUrl)?.let { return it }
        ProductUrlNormalizer.inferTitleFromUrl(url)?.let { return it }

        return null
    }
}
