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

        val title = content.html?.let { parser.extractTitleFromHtml(it, url) }
        val variants = when {
            content.variants.size > 1 -> content.variants
            content.html != null -> {
                val parsed = parser.parseHtml(content.html, url)
                if (parsed is ParseResult.Success && parsed.variants.isNotEmpty()) {
                    parsed.variants
                } else {
                    content.variants
                }
            }
            else -> content.variants
        }

        if (variants.isEmpty()) {
            return ParseResult.Error("Не удалось определить цены по объёмам")
        }

        val resolvedTitle = title
            ?: (content.html?.let { parser.parseHtml(it, url) } as? ParseResult.Success)?.title
            ?: return ParseResult.Error("Не удалось определить название товара")

        return ParseResult.Success(title = resolvedTitle, variants = variants)
    }
}
