package com.example.otsled.data.parser

import com.example.otsled.data.settings.ParseSessionStore

/**
 * Загрузка страницы товара с выбором способа.
 *
 * Быстрый путь — обычный HTTP-запрос. Он ломается каждый раз, когда сайт включает проверку браузера,
 * и тогда нужен WebView: он медленный (секунд 10-35), но исполняет JavaScript защиты.
 * Порядок путей задаёт [ParseSessionStore], чтобы не жечь трафик и батарею на заведомо
 * бесплодных HTTP-запросах, пока сессия не «прогрета».
 */
class PricePageLoader(
    private val parser: AllureParfumPriceParser,
    private val webViewFetcher: WebViewPriceFetcher,
    private val sessionStore: ParseSessionStore? = null,
) {
    suspend fun fetchAndParse(rawUrl: String): ParseResult {
        val url = ProductUrlNormalizer.normalize(rawUrl)
            ?: return ParseResult.Error(URL_HINT, ParseResult.Kind.PARSE)

        if (!ProductUrlNormalizer.isSupportedUrl(url)) {
            return ParseResult.Error(ONLY_ALLURE_HINT, ParseResult.Kind.PARSE)
        }

        val preferWebView = sessionStore?.shouldPreferWebView() == true
        var httpError: ParseResult.Error? = null

        if (!preferWebView) {
            when (val result = parser.fetchAndParse(url)) {
                is ParseResult.Success -> {
                    if (result.variants.isNotEmpty()) {
                        sessionStore?.markSuccess()
                        return result
                    }
                    httpError = ParseResult.Error(
                        "Не удалось определить цены по объёмам",
                        ParseResult.Kind.PARSE,
                    )
                }
                is ParseResult.Error -> {
                    httpError = result
                    if (result.kind == ParseResult.Kind.BOT_CHALLENGE) {
                        // Запоминаем: следующие проверки в этой пачке пойдут сразу в WebView.
                        sessionStore?.markChallengeHit()
                    }
                }
            }
        }

        // Товар физически удалён — WebView ничего не найдёт, а 30 секунд потратит.
        if (httpError?.kind == ParseResult.Kind.NOT_FOUND) return httpError

        val content = webViewFetcher.fetchContent(url)
            ?: return httpError ?: ParseResult.Error(LOAD_FAILED_HINT, ParseResult.Kind.NETWORK)

        if (content.challenge) {
            sessionStore?.markChallengeHit()
            return ParseResult.Error(
                "${AllureParfumPriceParser.CHALLENGE_MESSAGE}, попробуйте проверить позже",
                ParseResult.Kind.BOT_CHALLENGE,
            )
        }

        val variants = when {
            content.variants.size > 1 -> content.variants
            content.html != null -> {
                val parsed = parser.parseHtmlOrNull(content.html, url, PriceSource.WEBVIEW)
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
            if (content.loadFailed) {
                return httpError ?: ParseResult.Error(LOAD_FAILED_HINT, ParseResult.Kind.NETWORK)
            }
            val outOfStock = httpError?.kind == ParseResult.Kind.OUT_OF_STOCK ||
                !content.html.isNullOrBlank()
            return ParseResult.Error(
                NO_PRICES_HINT,
                if (outOfStock) ParseResult.Kind.OUT_OF_STOCK else ParseResult.Kind.PARSE,
            )
        }

        val resolvedTitle = resolveTitle(rawUrl, url, content)
            ?: return ParseResult.Error(TITLE_HINT, ParseResult.Kind.PARSE)

        sessionStore?.markSuccess()
        return ParseResult.Success(title = resolvedTitle, variants = variants, source = PriceSource.WEBVIEW)
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

    companion object {
        const val URL_HINT = "Укажите ссылку на allureparfum.ru"
        const val ONLY_ALLURE_HINT = "Поддерживаются только ссылки allureparfum.ru"
        const val LOAD_FAILED_HINT = "Не удалось загрузить страницу"
        const val NO_PRICES_HINT = "Не удалось определить цены по объёмам"
        const val TITLE_HINT = "Не удалось определить название товара"
    }
}
