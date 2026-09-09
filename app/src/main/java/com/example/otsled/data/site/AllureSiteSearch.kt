package com.example.otsled.data.site

import android.content.Context
import com.example.otsled.data.parser.AllureParfumPriceParser
import com.example.otsled.data.parser.BotProtection
import com.example.otsled.data.parser.WebViewPriceFetcher
import com.example.otsled.data.settings.ParseSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Поиск по названию на allureparfum.ru — чтобы не просить пользователя копировать ссылку.
 *
 * Путь тот же, что и у проверки цены: сначала быстрый HTTP, и только если сайт ответил
 * проверкой браузера (или обрезанным ответом) — WebView, который эту проверку исполняет.
 * Порядок диктует [ParseSessionStore], а не «угадываем каждый раз»: прогрев защиты стоит
 * десятки секунд, и жрать их на каждый запрос нельзя.
 *
 * Второе отличие от цены: здесь мы не можем видеть разметку выдачи, поэтому каждый исход
 * сопровождается [SiteSearchResult.note] — короткий диагноз (HTTP-код, размер страницы, число
 * найденных ссылок). Он попадает в «Журнал проверок» и в UI: без него «пусто на телефоне»
 * неотличимо от «сайт изменился» и от «мы не дождались».
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
        val notes = mutableListOf<String>()
        var challengeSeen = false

        if (sessionStore?.shouldPreferWebView() == true) {
            notes += "HTTP пропущен (после проверки браузера идём в WebView)"
        } else {
            when (val fetched = fetchHtml(url)) {
                is Fetch.Ok -> {
                    val links = SiteSearchQuery.countProductLinks(fetched.html)
                    notes += "HTTP 200, ${kib(fetched.html)}, ссылок на товары $links"
                    val hits = SiteSearchQuery.extractHits(fetched.html, normalizedQuery)
                    if (hits.isNotEmpty()) {
                        sessionStore?.markSuccess()
                        return@withContext SiteSearchResult.Success(
                            query = normalizedQuery,
                            hits = hits,
                            note = notes.summary(),
                        )
                    }
                    if (!looksBlocked(fetched.html)) {
                        // Страница ответа дошла целиком и это не заглушка: значит совпадений нет
                        // (или выдача устроена иначе) — WebView тут ничего не добавит.
                        return@withContext SiteSearchResult.Success(
                            query = normalizedQuery,
                            hits = emptyList(),
                            note = notes.summary(),
                        )
                    }
                    sessionStore?.markChallengeHit()
                    challengeSeen = true
                    notes += "HTTP: проверка браузера"
                }

                is Fetch.Challenge -> {
                    sessionStore?.markChallengeHit()
                    challengeSeen = true
                    notes += "HTTP: проверка браузера"
                }

                is Fetch.HttpStatus -> {
                    notes += "HTTP ${fetched.code}: ${fetched.message}"
                    if (fetched.code == 404) {
                        // Поиска на сайте нет — WebView тут не поможет, страница не появится.
                        return@withContext SiteSearchResult.Error(
                            NO_SEARCH_PAGE_HINT,
                            SiteSearchResult.Kind.NOT_FOUND,
                            note = notes.summary(),
                        )
                    }
                }

                is Fetch.Failed -> notes += "HTTP: ${fetched.message}"
            }
        }

        // Первая попытка часто уходит в незавершённую проверку браузера: куки проставляются
        // во время неё, поэтому одна пауза и повтор решают исход, а повторять бесконечно нельзя.
        var lastHtml: String? = null
        var lastLoadFailed = false
        for (attempt in 1..WEBVIEW_ATTEMPTS) {
            val content = webViewFetcher.fetchContent(
                rawUrl = url,
                readyWhen = { html -> !html.isNullOrBlank() },
            )
            lastHtml = content?.html
            lastLoadFailed = content?.loadFailed == true

            val links = SiteSearchQuery.countProductLinks(lastHtml)
            notes += "WebView #$attempt: " + when {
                lastHtml.isNullOrBlank() -> "страницу не отдали"
                else -> "${kib(lastHtml)}, ссылок на товары $links"
            }
            if (content?.challenge == true) {
                challengeSeen = true
                notes += "WebView #$attempt: проверка браузера не пройдена"
            }

            val hits = SiteSearchQuery.extractHits(lastHtml, normalizedQuery)
            if (hits.isNotEmpty()) {
                sessionStore?.markSuccess()
                return@withContext SiteSearchResult.Success(
                    query = normalizedQuery,
                    hits = hits,
                    viaWebView = true,
                    note = notes.summary(),
                )
            }

            val pageWasRendered = !lastHtml.isNullOrBlank() && content?.challenge != true
            if (pageWasRendered || attempt == WEBVIEW_ATTEMPTS) break

            if (attempt == 1) delay(WEBVIEW_RETRY_DELAY_MS)
        }

        val summary = notes.summary()
        val links = SiteSearchQuery.countProductLinks(lastHtml)
        when {
            // Страница реальная (не заглушка) и ссылки на товары в ней есть, а строк нет —
            // это наш разбор не совпал с выдачей, а не «совпадений нет».
            !lastHtml.isNullOrBlank() && links > 0 -> SiteSearchResult.Error(
                PARSE_HINT,
                SiteSearchResult.Kind.PARSE,
                note = summary,
            )

            // Страница реальная, ссылок нет — честно говорим «не найдено», как на HTTP-пути.
            !lastHtml.isNullOrBlank() -> SiteSearchResult.Success(
                query = normalizedQuery,
                hits = emptyList(),
                viaWebView = true,
                note = summary,
            )

            // Страниц так и не отдали: если по ходу была проверка браузера — виновата она.
            challengeSeen -> {
                sessionStore?.markChallengeHit()
                SiteSearchResult.Error(
                    CHALLENGE_HINT,
                    SiteSearchResult.Kind.BOT_CHALLENGE,
                    note = summary,
                )
            }

            else -> SiteSearchResult.Error(
                if (lastLoadFailed) LOAD_FAILED_HINT else CHALLENGE_HINT,
                if (lastLoadFailed) SiteSearchResult.Kind.NETWORK else SiteSearchResult.Kind.BOT_CHALLENGE,
                note = summary,
            )
        }
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
                    html.isBlank() -> Fetch.HttpStatus(200, "пустой ответ сайта")
                    else -> Fetch.Ok(html)
                }
            }
        }.getOrElse { error ->
            if (error is java.io.IOException) {
                Fetch.Failed("сеть недоступна (${error.javaClass.simpleName})")
            } else {
                Fetch.Failed(error.message ?: "неизвестная ошибка сети")
            }
        }
    }

    /** Заглушка анти-бота или обрезанный ответ: в этих случаях есть смысл переспросить через WebView. */
    private fun looksBlocked(html: String): Boolean =
        BotProtection.isChallengeHtml(html) || html.length < SHORT_BODY_LIMIT

    private fun kib(html: String): String = "${html.length / 1024} КБ"

    private fun List<String>.summary(): String? = takeIf { it.isNotEmpty() }?.joinToString("; ")

    private sealed interface Fetch {
        data class Ok(val html: String) : Fetch
        data class HttpStatus(val code: Int, val message: String) : Fetch
        object Challenge : Fetch
        data class Failed(val message: String) : Fetch
    }

    companion object {
        private const val SHORT_BODY_LIMIT = 3_000
        private const val WEBVIEW_ATTEMPTS = 2
        private const val WEBVIEW_RETRY_DELAY_MS = 2_500L

        const val TOO_SHORT_HINT = "Введите хотя бы 3 символа названия"
        const val LOAD_FAILED_HINT = "Не удалось загрузить страницу поиска"
        const val NO_SEARCH_PAGE_HINT =
            "На сайте нет страницы поиска (HTTP 404) — вставьте ссылку на товар вручную"
        const val PARSE_HINT =
            "Страницу поиска получили, но строк в ней не увидели: похоже, выдача изменилась"
        /** Подсказка осмысленная: проверка любого товара по ссылке прогревает те же куки. */
        const val CHALLENGE_HINT =
            "Сайт запросил проверку браузера. Попробуйте через пару минут или сначала добавьте " +
            "товар по ссылке — после успешной проверки поиск идёт быстрее"
    }
}
