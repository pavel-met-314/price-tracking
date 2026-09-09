package com.example.otsled.data.parser

/**
 * allureparfum.ru стоит за JavaScript-проверкой браузера: вместо товара сервер может отдать
 * заглушку «Выполняется проверка вашего веб-браузера…». Такую страницу нельзя отличить от
 * «товара нет» по HTTP-коду — ответ всегда 200, поэтому единственный надёжный признак — разметка.
 *
 * Маркеры собраны из разных движков защиты (собственный скрипт сайта, Cloudflare, DDoS-Guard),
 * потому что набор зависит от нагрузки на сайт и от того, какой межселенный CDN его обслуживает.
 */
object BotProtection {

    /** Подстроки, встречающиеся только на страницах-заглушках анти-бота. */
    private val CHALLENGE_MARKERS = listOf(
        "js-challenge-script",
        "jsch._jschallenge",
        "выполняется проверка",
        "проверка вашего",
        "проверяем ваш браузер",
        "ожидите",
        "cf-browser-verification",
        "cf_chl_",
        "just a moment",
        "checking your browser",
        "verifying you are human",
        "ddos-guard",
        "qrux",
        "captcha",
        "access denied",
        "доступ ограничен",
    )

    /** Признаки того, что короткая страница без цен — именно заглушка, а не битый ответ. */
    private val CHALLENGE_HINT_REGEX = Regex(
        "(проверк|верификац|web-browser|webbrowser|browser|javascript|challenge|security)",
        RegexOption.IGNORE_CASE,
    )

    /** Максимальная длина «заглушки»: настоящая страница товара вместе со скриптами всегда длиннее. */
    private const val STUB_HTML_LIMIT = 40_000

    fun isChallengeHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val lower = html.lowercase()
        if (CHALLENGE_MARKERS.any { lower.contains(it) }) return true

        // Комбинация: страница короткая, цен нет, объёмов нет, но есть слова про проверку браузера.
        val looksLikeProductPage = lower.contains("руб") || lower.contains("₽") || lower.contains("мл")
        return lower.length < STUB_HTML_LIMIT &&
            !looksLikeProductPage &&
            CHALLENGE_HINT_REGEX.containsMatchIn(lower)
    }

    fun describeHttpError(code: Int): String? = when (code) {
        403 -> "Сайт запретил запрос (403) — нужна проверка браузера"
        429 -> "Слишком много запросов (429) — сайт временно ограничил доступ"
        404 -> "Страница товара не найдена (404) — возможно, товар снят с продажи"
        503 -> "Сайт недоступен (503)"
        else -> null
    }
}
