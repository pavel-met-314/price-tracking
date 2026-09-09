package com.example.otsled.data.site

import android.content.Context
import com.example.otsled.data.parser.AllureParfumPriceParser
import com.example.otsled.data.parser.BotProtection
import com.example.otsled.data.parser.WebViewPriceFetcher
import com.example.otsled.data.settings.ParseSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Поиск по названию на allureparfum.ru — чтобы не просить пользователя копировать ссылку.
 *
 * Тот же путь, что и у проверки цены: сначала быстрый HTTP, и только если сайт ответил
 * проверкой браузера (или пустым ответом) — WebView, который эту проверку исполняет. Порядок
 * диктует [ParseSessionStore], а не «угадываем каждый раз»: прогрев защиты стоит ~10 секунд,
 * и жрать их на каждый запрос поиска нельзя.
 */
class AllureSiteSearch(
    context: Context,
    private val webViewFetcher: WebViewPriceFetcher,
    private val sessionStore: ParseSessionStore? = null,
    private val client: OkHttpClient = AllureParfumPriceParser.defaultClient(context),
) {

    suspend fun search(query: String): SiteSearchResult = withContext(Dispatchers.IO) {
        if (SiteSearchQuery.isQueryTooShort(query)) {
            return@withContext SiteSearchResult.Error(TOO_SHORT_HINT, SiteSearchResult.Kind.TOO_SHORT)
        }

        val normalizedQuery = query.trim()
        val url = SiteSearchQuery.searchUrl(normalizedQuery)
        var fallbackReason: SiteSearchResult.Error? = null

        if (sessionStore?.shouldPreferWebView() != true) {
            when (val fetched = fetchHtml(url)) {
                is Fetch.Ok -> {
                    val hits = SiteSearchQuery.extractHits(fetched.html, normalizedQuery)
                    if (hits.isNotEmpty()) {
                        sessionStore?.markSuccess()
                        return@withContext SiteSearchResult.Success(normalizedQuery, hits)
                    }
                    // Настоящая страница поиска без результатов — это «не найдено», повтор через
                    // WebView ничего не добавит, только сожжёт десять секунд.
                    if (!looksBlocked(fetched.html)) {
                        return@withContext SiteSearchResult.Success(normalizedQuery, emptyList())
                    }
                    sessionStore?.markChallengeHit()
                    fallbackReason = SiteSearchResult.Error(
                        AllureParfumPriceParser.CHALLENGE_MESSAGE,
                        SiteSearchResult.Kind.BOT_CHALLENGE,
                    )
                }

                is Fetch.Challenge -> {
                    sessionStore?.markChallengeHit()
                    fallbackReason = SiteSearchResult.Error(
                        AllureParfumPriceParser.CHALLENGE_MESSAGE,
                        SiteSearchResult.Kind.BOT_CHALLENGE,
                    )
                }

                is Fetch.HttpStatus -> {
                    if (fetched.code == 404) {
                        // Поиска на сайте нет — WebView тут не поможет, страница не появится.
                        return@withContext SiteSearchResult.Error(
                            "На сайте нет страницы поиска (HTTP 404) — вставьте ссылку на товар вручную",
                            SiteSearchResult.Kind.NOT_FOUND,
                        )
                    }
                    fallbackReason = SiteSearchResult.Error(fetched.message, SiteSearchResult.Kind.NETWORK)
                }

                is Fetch.Failed -> fallbackReason = SiteSearchResult.Error(fetched.message, SiteSearchResult.Kind.NETWORK)
            }
        }

        val content = webViewFetcher.fetchContent(
            rawUrl = url,
            readyWhen = { html -> SiteSearchQuery.hasProductLinks(html) },
        )
        val hits = SiteSearchQuery.extractHits(content?.html, normalizedQuery)
        if (hits.isNotEmpty()) {
            sessionStore?.markSuccess()
            return@withContext SiteSearchResult.Success(normalizedQuery, hits, viaWebView = true)
        }

        if (content == null) {
            return@withContext fallbackReason
                ?: SiteSearchResult.Error(LOAD_FAILED_HINT, SiteSearchResult.Kind.NETWORK)
        }
        if (content.challenge) {
            sessionStore?.markChallengeHit()
            return@withContext SiteSearchResult.Error(
                "${AllureParfumPriceParser.CHALLENGE_MESSAGE}, попробуйте через пару минут",
                SiteSearchResult.Kind.BOT_CHALLENGE,
            )
        }
        if (content.loadFailed) {
            return@withContext fallbackReason
                ?: SiteSearchResult.Error(LOAD_FAILED_HINT, SiteSearchResult.Kind.NETWORK)
        }
        SiteSearchResult.Success(normalizedQuery, emptyList(), viaWebView = true)
    }

    private suspend fun fetchHtml(url: String): Fetch = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", AllureParfumPriceParser.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> Fetch.HttpStatus(
                        code = response.code,
                        message = BotProtection.describeHttpError(response.code) ?: "HTTP ${response.code}",
                    )

                    BotProtection.isChallengeHtml(html) -> Fetch.Challenge
                    html.isBlank() -> Fetch.HttpStatus(200, "Пустой ответ сайта")
                    else -> Fetch.Ok(html)
                }
            }
        }.getOrElse { error ->
            if (error is java.io.IOException) {
                Fetch.Failed("Сеть недоступна: ${error.message ?: error.javaClass.simpleName}")
            } else {
                Fetch.Failed(error.message ?: "Неизвестная ошибка сети")
            }
        }
    }

    /** Заглушка анти-бота или обрезанный ответ: в этих случаях есть смысл переспросить через WebView. */
    private fun looksBlocked(html: String): Boolean =
        BotProtection.isChallengeHtml(html) || html.length < SHORT_BODY_LIMIT

    private sealed interface Fetch {
        data class Ok(val html: String) : Fetch
        data class HttpStatus(val code: Int, val message: String) : Fetch
        object Challenge : Fetch
        data class Failed(val message: String) : Fetch
    }

    companion object {
        private const val SHORT_BODY_LIMIT = 3_000

        const val TOO_SHORT_HINT = "Введите хотя бы 3 символа названия"
        const val LOAD_FAILED_HINT = "Не удалось загрузить страницу поиска"
    }
}
