package com.example.otsled.data.parser

import com.example.otsled.data.settings.ParseSessionStore

/**
 * Загрузка страницы товара с выбором способа.
 *
 * Быстрый путь — обычный HTTP-запрос (`parser`). Он ломается каждый раз, когда сайт включает
 * проверку браузера, и тогда нужен WebView: медленный (10–35 секунд), но исполняет JavaScript
 * защиты. Порядок путей задаёт [ParseSessionStore], чтобы не жечь трафик и батарею на заведомо
 * бесплодных HTTP-запросах, пока сессия не «прогрета».
 *
 * Правило, которое здесь главное: **отказ одного пути не закрывает другой**. Кулдаун после
 * «проверьте браузер» означает «сначала WebView», а не «только WebView»: иначе одна неудачная
 * проверка браузера (сайт ужесточил защиту, куки протухли, WebView не успел) закрывала бы и
 * фоновые проверки, и добавление товара по ссылке на целый час, хотя быстрый путь работал.
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
            val outcome = httpOnce(url)
            outcome.success?.let { return it }
            httpError = outcome.error
        }

        // Товар физически удалён — WebView ничего не найдёт, а 30 секунд потратит.
        if (httpError?.kind == ParseResult.Kind.NOT_FOUND) return httpError

        val content = webViewFetcher.fetchContent(url)
        val webViewVariants = content?.let { resolveVariants(it, url) }.orEmpty()

        if (webViewVariants.isNotEmpty()) {
            val title = content?.let { resolveTitle(rawUrl, url, it) }
                ?: return ParseResult.Error(TITLE_HINT, ParseResult.Kind.PARSE)
            sessionStore?.markSuccess()
            return ParseResult.Success(title = title, variants = webViewVariants, source = PriceSource.WEBVIEW)
        }

        // WebView цен не дал. Если HTTP мы пропустили по кулдауну, теперь он обязан отработать.
        if (preferWebView) {
            val retry = httpOnce(url)
            retry.success?.let { return it }
            httpError = retry.error ?: httpError
        }

        return failure(content, httpError)
    }

    /**
     * Одна попытка быстрого пути. Успех возвращается отдельно от ошибки именно потому, что этот
     * путь теперь вызывается из двух мест (основной и обходной), и «верни результат наверх»
     * должно быть одинаковым в обоих.
     */
    private suspend fun httpOnce(url: String): HttpOutcome = when (val result = parser.fetchAndParse(url)) {
        is ParseResult.Success -> {
            if (result.variants.isNotEmpty()) {
                sessionStore?.markSuccess()
                HttpOutcome(success = result, error = null)
            } else {
                HttpOutcome(success = null, error = ParseResult.Error(NO_PRICES_HINT, ParseResult.Kind.PARSE))
            }
        }

        is ParseResult.Error -> {
            if (result.kind == ParseResult.Kind.BOT_CHALLENGE) {
                // Запоминаем: следующие проверки в этой пачке начнут с WebView.
                sessionStore?.markChallengeHit()
            }
            HttpOutcome(success = null, error = result)
        }
    }

    private fun resolveVariants(content: WebPageContent, url: String): List<ParsedProductVariant> = when {
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

    private fun failure(content: WebPageContent?, httpError: ParseResult.Error?): ParseResult {
        if (content == null) {
            return httpError ?: ParseResult.Error(LOAD_FAILED_HINT, ParseResult.Kind.NETWORK)
        }

        if (content.challenge) {
            sessionStore?.markChallengeHit()
            // Текст страницы говорит больше, чем сам факт блокировки: автотест пройдёт сам, капчу
            // должен закрыть человек, а «доступ ограничен с вашего IP» вообще не наша история.
            val details = content.stubText?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            return ParseResult.Error(
                "${AllureParfumPriceParser.CHALLENGE_MESSAGE}, попробуйте проверить позже$details",
                ParseResult.Kind.BOT_CHALLENGE,
            )
        }

        if (content.loadFailed) {
            return httpError ?: ParseResult.Error(LOAD_FAILED_HINT, ParseResult.Kind.NETWORK)
        }

        val outOfStock = httpError?.kind == ParseResult.Kind.OUT_OF_STOCK || !content.html.isNullOrBlank()
        return ParseResult.Error(
            NO_PRICES_HINT,
            if (outOfStock) ParseResult.Kind.OUT_OF_STOCK else ParseResult.Kind.PARSE,
        )
    }

    private fun resolveTitle(rawUrl: String, url: String, content: WebPageContent): String? {
        content.title?.takeIf { it.isNotBlank() }?.let { return it }

        content.html?.let { html ->
            parser.extractTitleFromHtml(html, url)?.let { return it }
            parser.parseHtmlOrNull(html, url)?.title?.let { return it }
        }

        ProductUrlNormalizer.inferTitleFromUrl(rawUrl)?.let { return it }
        return ProductUrlNormalizer.inferTitleFromUrl(url)
    }

    /** Результат быстрого пути: либо готовый ответ, либо причина, по которой он не годится. */
    private data class HttpOutcome(
        val success: ParseResult.Success?,
        val error: ParseResult.Error?,
    )

    companion object {
        const val URL_HINT = "Укажите ссылку на allureparfum.ru"
        const val ONLY_ALLURE_HINT = "Поддерживаются только ссылки allureparfum.ru"
        const val LOAD_FAILED_HINT = "Не удалось загрузить страницу"
        const val NO_PRICES_HINT = "Не удалось определить цены по объёмам"
        const val TITLE_HINT = "Не удалось определить название товара"
    }
}
