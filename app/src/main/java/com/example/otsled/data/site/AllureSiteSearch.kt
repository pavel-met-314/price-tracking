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
 * Путь тот же, что и у проверки цены: HTTP и WebView, порядок выбирает [ParseSessionStore], а
 * отказ одного пути не закрывает другой (см. [com.example.otsled.data.parser.PricePageLoader]).
 * Прогрев защиты общий: куки лежат в одном CookieManager, поэтому успешно пройденная проверка
 * товара делает доступным и поиск, и наоборот.
 *
 * Второе отличие от цены: разметку выдачи мы живьём не видим, поэтому каждый исход
 * сопровождается [SiteSearchResult.note] — коротким диагнозом (что пробовали, что получили,
 * сколько ссылок на товары увидели). Он показывается в интерфейсе и попадает в «Журнал
 * проверок»: без него «пусто на телефоне» неотличимо от «сайт изменился» и от «мы не дождались».
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
        val preferWebView = sessionStore?.shouldPreferWebView() == true
        var challengeSeen = false
        var links = 0

        if (preferWebView) {
            notes += "HTTP отложен: после проверки браузера сначала WebView"
        } else {
            val attempt = httpAttempt(url, normalizedQuery, notes, "HTTP")
            attempt.fatal?.let { return@withContext it.withNote(notes) }
            links = maxOf(links, attempt.links)
            if (attempt.hits.isNotEmpty()) {
                sessionStore?.markSuccess()
                return@withContext SiteSearchResult.Success(
                    query = normalizedQuery,
                    hits = attempt.hits,
                    note = notes.summary(),
                )
            }
            if (!attempt.blocked) {
                // Страница пришла целиком и это не заглушка: совпадений нет, WebView ничего не
                // добавит — только сожжёт десятки секунд.
                return@withContext SiteSearchResult.Success(
                    query = normalizedQuery,
                    hits = emptyList(),
                    note = notes.summary(),
                )
            }
            challengeSeen = true
        }

        // Первая попытка обычно ловит незавершённую проверку браузера; куки после неё уже
        // проставлены, поэтому один повтор через паузу решает исход. Крутиться бесконечно нельзя.
        var lastHtml: String? = null
        var loadFailed = false
        for (attempt in 1..WEBVIEW_ATTEMPTS) {
            val content = webViewFetcher.fetchContent(
                rawUrl = url,
                readyWhen = { html -> SiteSearchQuery.isReadyForExtraction(html) },
            )
            lastHtml = content?.html
            loadFailed = content?.loadFailed == true
            links = maxOf(links, SiteSearchQuery.countProductLinks(lastHtml))

            notes += "WebView #$attempt: " + when {
                lastHtml.isNullOrBlank() -> "страницу не отдали"
                else -> "${kib(lastHtml)}, ссылок на товары $links"
            }
            if (content?.challenge == true) {
                challengeSeen = true
                notes += "WebView #$attempt: " +
                    (content.stubText?.takeIf { it.isNotBlank() }?.let { "заглушка «$it»" } ?: "проверка браузера не пройдена")
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

            // Если страница отрисована и готова к разбору, но строк нет — второй заход ничего не
            // изменит; если же мы получили полустаницу (только шапку), одна пауза и повтор решают.
            if (SiteSearchQuery.isReadyForExtraction(lastHtml) || attempt == WEBVIEW_ATTEMPTS) break
            delay(WEBVIEW_RETRY_DELAY_MS)
        }

        // Обходной путь: если быстрый HTTP был отложен из-за кулдауна, пробуем его сейчас.
        if (preferWebView) {
            val attempt = httpAttempt(url, normalizedQuery, notes, "fallback HTTP")
            attempt.fatal?.let { return@withContext it.withNote(notes) }
            links = maxOf(links, attempt.links)
            if (attempt.hits.isNotEmpty()) {
                sessionStore?.markSuccess()
                return@withContext SiteSearchResult.Success(
                    query = normalizedQuery,
                    hits = attempt.hits,
                    note = notes.summary(),
                )
            }
        }

        val summary = notes.summary()
        val parsed = SiteSearchQuery.isReadyForExtraction(lastHtml)
        when {
            // Страница реальная и ссылки на товары в ней есть, а строк нет — это наш разбор не
            // совпал с выдачей, а не «совпадений нет».
            parsed && links > 0 -> SiteSearchResult.Error(
                PARSE_HINT,
                SiteSearchResult.Kind.PARSE,
                note = summary,
            )

            // Страница готова и ссылок нет — честно «не найдено».
            parsed -> SiteSearchResult.Success(
                query = normalizedQuery,
                hits = emptyList(),
                viaWebView = true,
                note = summary,
            )

            // Полустраница (одна шапка) — это не «не найдено» и не блокировка: не дождались.
            // Ошибка помечается повторяемой, чтобы кнопка «Повторить поиск» осталась на экране.
            !lastHtml.isNullOrBlank() -> SiteSearchResult.Error(
                NOT_WAITED_HINT,
                if (challengeSeen) SiteSearchResult.Kind.BOT_CHALLENGE else SiteSearchResult.Kind.NETWORK,
                note = summary,
            )

            challengeSeen -> {
                sessionStore?.markChallengeHit()
                SiteSearchResult.Error(
                    CHALLENGE_HINT,
                    SiteSearchResult.Kind.BOT_CHALLENGE,
                    note = summary,
                )
            }

            loadFailed -> SiteSearchResult.Error(
                LOAD_FAILED_HINT,
                SiteSearchResult.Kind.NETWORK,
                note = summary,
            )

            else -> {
                sessionStore?.markChallengeHit()
                SiteSearchResult.Error(
                    CHALLENGE_HINT,
                    SiteSearchResult.Kind.BOT_CHALLENGE,
                    note = summary,
                )
            }
        }
    }

    /**
     * Быстрый путь: один GET + разбор. [label] попадает в диагноз, чтобы «HTTP» и «fallback HTTP»
     * в журнале не выглядели одинаково.
     */
    private suspend fun httpAttempt(
        url: String,
        query: String,
        notes: MutableList<String>,
        label: String,
    ): HttpAttempt {
        val fetched = runCatching {
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

        return when (fetched) {
            is Fetch.Ok -> {
                val linkCount = SiteSearchQuery.countProductLinks(fetched.html)
                notes += "$label ${kib(fetched.html)}, ссылок на товары $linkCount"
                val blocked = BotProtection.isChallengeHtml(fetched.html) || fetched.html.length < SHORT_BODY_LIMIT
                HttpAttempt(
                    hits = SiteSearchQuery.extractHits(fetched.html, query),
                    links = linkCount,
                    blocked = blocked,
                    fatal = null,
                )
            }

            is Fetch.Challenge -> {
                notes += "$label: страница проверки браузера"
                sessionStore?.markChallengeHit()
                HttpAttempt(emptyList(), 0, blocked = true, fatal = null)
            }

            is Fetch.HttpStatus -> {
                notes += "$label ${fetched.code}: ${fetched.message}"
                val fatal = if (fetched.code == 404) {
                    // Поиска на сайте нет — WebView тут не поможет, страница не появится.
                    SiteSearchResult.Error(NO_SEARCH_PAGE_HINT, SiteSearchResult.Kind.NOT_FOUND)
                } else {
                    null
                }
                HttpAttempt(emptyList(), 0, blocked = fatal == null, fatal = fatal)
            }

            is Fetch.Failed -> {
                notes += "$label: ${fetched.message}"
                HttpAttempt(emptyList(), 0, blocked = true, fatal = null)
            }
        }
    }

    private fun SiteSearchResult.Error.withNote(notes: List<String>): SiteSearchResult.Error =
        if (notes.isEmpty()) this else copy(note = notes.summary())

    private fun kib(html: String): String = "${html.length / 1024} КБ"

    private fun List<String>.summary(): String? = takeIf { it.isNotEmpty() }?.joinToString("; ")

    private class HttpAttempt(
        val hits: List<SiteSearchHit>,
        val links: Int,
        /** true — страница не получена (заглушка/обрезана/сеть), есть смысл во втором пути. */
        val blocked: Boolean,
        /** Ошибка, при которой второй путь бессмысленен (страницы поиска нет вовсе). */
        val fatal: SiteSearchResult.Error?,
    )

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

        /** Полустраница: повтор имеет смысл, обычно помогает второй заход после прогрева. */
        const val NOT_WAITED_HINT =
            "Страница поиска загрузилась не полностью — попробуйте повторить через минуту"

        /** Подсказка осмысленная: проверка любого товара по ссылке прогревает те же куки. */
        const val CHALLENGE_HINT =
            "Сайт запросил проверку браузера. Пройдите её в настройках («Проверка браузера») " +
                "или попробуйте через пару минут"
    }
}
