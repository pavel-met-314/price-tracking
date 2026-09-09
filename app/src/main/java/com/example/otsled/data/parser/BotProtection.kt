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

    /**
     * Признаки того, что короткая страница без цен — именно заглушка, а не битый ответ. Только
     * однозначные обороты: слова «browser», «javascript» и «security» встречаются в разметке любой
     * живой страницы (теги скриптов, meta viewport), и с ними половина живых страниц объявлялась блокировкой.
     */
    private val CHALLENGE_HINT_REGEX = Regex(
        "(проверк|верификац|web-?browser-?verification|just a moment|checking your browser|" +
            "access denied|attention required|unusual traffic|human verification|cloudflare|" +
            "ddos-guard|captcha|\\u043d\\u0435 \\u0440\\u043e\\u0431\\u043e\\u0442|" +
            "\\u0434\\u043e\\u0441\\u0442\\u0443\\u043f \\u043e\\u0433\\u0440\\u0430\\u043d\\u0438\\u0447|" +
            "\\u0437\\u0430\\u043f\\u0440\\u043e\\u0441 \\u043e\\u0442\\u043a\\u043b\\u043e\\u043d)",
        RegexOption.IGNORE_CASE,
    )

    private val SCRIPT_BLOCK_REGEX = Regex("(?is)<script[^>]*>.*?</script>")
    private val STYLE_BLOCK_REGEX = Regex("(?is)<style[^>]*>.*?</style>")
    private val TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** Максимальная длина «заглушки»: настоящая страница товара вместе со скриптами всегда длиннее. */
    private const val STUB_HTML_LIMIT = 40_000

    /**
     * Предел «совсем короткой» страницы: меньше весит только обложка проверки браузера. Нужно для
     * случая «маркер есть и признаки контента есть» — живая страница выдачи десятки килобайт,
     * а заглушка, завёрнутая в шаблон сайта, остаётся крошечной.
     */
    private const val TINY_PAGE_LIMIT = 3_000

    /**
     * Страница получена настоящая: любой из этих маркеров означает, что контент на месте.
     * Заглушка анти-бота ссылок на разделы, цен и формы поиска не содержит вообще.
     */
    private val REAL_CONTENT_MARKERS = listOf(
        "/katalog/",
        "/brend/",
        "s_search",
        "результат",
        "ничего не найдено",
        "руб",
        "\u20bd",
        " мл",
        "корзин",
        "заказ",
    )

    /**
     * true — если вместо страницы была заглушка анти-бота.
     *
     * Раньше достаточно было одного маркера, и это ломало разбор реальных страниц: на странице
     * поиска есть скрипт со словом «captcha» и упоминанием «browser», а цен там по определению нет
     * — живая проверка на телефоне показывала «сайт запросил проверку браузера» там, где страница
     * загрузилась нормально (в выжимке было видно меню сайта). Теперь контент перевешивает маркер:
     * блокировкой считаем только страницу без какого-либо содержимого либо совсем короткую.
     *
     * Пустая страница — не заглушка, а отсутствие ответа: её ловят вызывающие стороны, потому что
     * «заглушка» требует от пользователя действий, а пустой ответ — повтора.
     */
    fun isChallengeHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val lower = html.lowercase()
        val markerHit = CHALLENGE_MARKERS.any { lower.contains(it) }
        val realContent = REAL_CONTENT_MARKERS.any { lower.contains(it) }

        if (realContent) return markerHit && lower.length < TINY_PAGE_LIMIT

        if (markerHit) return true

        // Комбинация: страница короткая, содержимого нет, но есть слова про проверку браузера.
        return lower.length < STUB_HTML_LIMIT && CHALLENGE_HINT_REGEX.containsMatchIn(lower)
    }

    /**
     * Короткая текстовая выжимка из страницы-заглушки. Нужна потому, что «Сайт запросил проверку
     * браузера» ничего не объясняет, а первая строка текста страницы сразу различает три разных
     * случая: автотест (пройдёт сам), капчу (нужен человек) и «доступ ограничен с вашего IP»
     * (приложение бессильно). Теги срезаются грубо: разметка неизвестна, а тащить ради одной
     * строки полный парсер нечем.
     */
    fun stubSummary(html: String?, maxLength: Int = 160): String? {
        if (html.isNullOrBlank()) return null

        // Тело страницы, причём вместе с «>» открывающего тега: иначе в начало выжимки
        // попадает мусор из атрибутов (<body class="…">).
        val bodyStart = html.indexOf("<body", ignoreCase = true)
        val source = if (bodyStart >= 0) html.substring(bodyStart).substringAfter('>') else html
        val text = source
            .substringBefore("</body>")
            .let { SCRIPT_BLOCK_REGEX.replace(it, " ") }
            .let { STYLE_BLOCK_REGEX.replace(it, " ") }
            .let { TAG_REGEX.replace(it, " ") }
            .replace('\u00A0', ' ')
            .let { WHITESPACE_REGEX.replace(it, " ") }
            .trim()

        if (text.isBlank()) return null
        return text.take(maxLength)
    }

    fun describeHttpError(code: Int): String? = when (code) {
        403 -> "Сайт запретил запрос (403) — нужна проверка браузера"
        429 -> "Слишком много запросов (429) — сайт временно ограничил доступ"
        404 -> "Страница товара не найдена (404) — возможно, товар снят с продажи"
        503 -> "Сайт недоступен (503)"
        else -> null
    }
}
