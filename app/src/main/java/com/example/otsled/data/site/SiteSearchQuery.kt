package com.example.otsled.data.site

import com.example.otsled.data.parser.BotProtection
import com.example.otsled.data.parser.PriceNormalizer
import com.example.otsled.data.parser.ProductUrlNormalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * Одна строка выдачи сайта. [price] может отсутствовать: страница поиска обязана дать название и
 * ссылку, а цену мы достанем при первой проверке товара.
 */
data class SiteSearchHit(
    val title: String,
    val url: String,
    val price: Double? = null,
    val volumeLabel: String? = null,
    /** true — строка выглядит результатом поиска (блок с ценой или подписанный контейнер). */
    val fromResultsBlock: Boolean = false,
)

/** Результат поиска по названию. Пустой список [SiteSearchResult.Success.hits] — не ошибка. */
sealed class SiteSearchResult {

    data class Success(
        val query: String,
        val hits: List<SiteSearchHit>,
        /** true — страницу отдавал WebView, то есть пришлось проходить проверку браузера. */
        val viaWebView: Boolean = false,
        /** Короткий диагноз для журнала: что пробовали и что получили. */
        val note: String? = null,
    ) : SiteSearchResult()

    data class Error(
        val message: String,
        val kind: Kind,
        val note: String? = null,
    ) : SiteSearchResult()

    enum class Kind {
        PARSE,
        BOT_CHALLENGE,
        NETWORK,
        NOT_FOUND,
        TOO_SHORT,
    }
}

/**
 * Разбор выдачи поиска allureparfum.ru.
 *
 * Живую разметку страницы поиска увидеть нельзя — сайт отвечает JS-проверкой браузера, поэтому
 * здесь намеренно нет ни одного селектора по классу: берутся все ссылки на страницы товаров
 * (`/katalog/…/<slug>.html`), а название, цена и объём ищутся вокруг них. Такой разбор переживает
 * смену шаблона; мусорные ссылки отсекаются по тегам-контейнерам (header/footer/nav/aside), а не по
 * именам классов.
 *
 * Название берётся не из текста ссылки: в карточке Allure ссылка ведёт на кнопку «Подробнее», а
 * бренд и товар подписаны отдельными строками. Поэтому имя собирается из текста карточки
 * ([productTitle]) и приводится к виду «Бренд - Товар».
 */
object SiteSearchQuery {

    const val BASE = "https://allureparfum.ru"

    private const val MAX_HITS = 24
    private const val MIN_QUERY_LENGTH = 3
    private const val TITLE_MAX_LENGTH = 160

    /** Больше этого текста в карточке товара быть не должно: длиннее — это уже список выдачи. */
    private const val MAX_CARD_TEXT_LENGTH = 700

    /** Строка бренда в «Бренд - Товар» не бывает длиннее; длинная строка — это описание, а не бренд. */
    private const val MAX_BRAND_LENGTH = 40

    /** Ссылка на страницу товара прямо в HTML — по ней понимаем, что страница готова к разбору. */
    private val PRODUCT_HREF_REGEX = Regex("""(?iu)/katalog/[^"'\s>]+\.html""", RegexOption.IGNORE_CASE)
    private val VOLUME_REGEX = Regex("""(?iu)(\d+(?:[.,]\d+)?)\s*мл\.?""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")

    /** Цена в строке: «360 - 22 235 руб.», «от 5 470 руб.», «22 100 ₽». Название так не выглядит. */
    private val PRICEISH_REGEX = Regex("(?iu)(руб|\u20bd|грн|тенге|\\d[\\s\u00A0]\\d)")

    /**
     * Начало строки, после которого текст — это подпись блока («Подробнее», «Быстрый просмотр»,
     * «Унисекс», «Семейство: …»), а не имя товара. Именно «подробнее» попадало в список вместо
     * названия: ссылкой в карточке Allure является кнопка, а заголовок товара лежит рядом.
     * Сопоставляем с началом строки и по основам слов: название, в котором такое слово просто
     * встречается внутри («Ganymede, тестер»), отброшено быть не должно.
     */
    private val NOISE_LINE_REGEX = Regex(
        """(?iu)^[\s.,:;!?()-]*(подробн|показат|результат|просмотр|корзин|купит|добав|сравн|избранн|""" +
            """отложен|поделит|скидк|акци|промоко|купон|балл|бонус|отзыв|комментар|оцен|рейтинг|""" +
            """артикул|налич|цена|стоимост|семейств|групп|унисекс|мужск|женск|детск|об[ъ]?(ем|ём)|""" +
            """доставк|оплат|возврат|пробник|быстр|для\s+\w+)""",
    )

    /** Бейджи карточки: «NEW», «ХИТ», «-30%» — отбрасывается только строка целиком. */
    private val BADGE_LINE_REGEX = Regex(
        """(?iu)^(new|sale|hit|top|хит|новинка|бестселлер|скидка|акция|%|-?\d+%)$""",
    )

    private val LETTERS_REGEX = Regex("(?iu)[\\p{L}]{2}")
    private val RESULT_BLOCK_REGEX = Regex("""(?iu)(search|result|offer|item|product|card)""", RegexOption.IGNORE_CASE)

    /** Вердикт страницы поиска без результатов: ждать появления товаров бессмысленно. */
    private val SEARCH_VERDICT_REGEX = Regex(
        "(?iu)(\u043d\u0438\u0447\u0435\u0433\u043e \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u043e|\u043d\u0435\u0442 \u0440\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442\u043e\u0432|\u043f\u043e\u0438\u0441\u043a \u043d\u0435 \u0434\u0430\u043b|\u0441\u043e\u0432\u043f\u0430\u0434\u0435\u043d\u0438\u0439 \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u043e|nothing found)",
        RegexOption.IGNORE_CASE,
    )

    private val CHROME_TAGS = setOf("header", "footer", "nav", "aside")

    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(cleanQuery(query), "UTF-8")
        // bitrix:search.page: q — запрос, s_search=Y — сам факт «это поиск», how=r — по релевантности.
        // Лишние параметры безвредны (Php их игнорирует), зато поиск работает и на компоненте
        // «расширенного поиска», и на простом.
        return "$BASE/search/?q=$encoded&s_search=Y&how=r"
    }

    fun isQueryTooShort(query: String): Boolean = cleanQuery(query).length < MIN_QUERY_LENGTH

    /**
     * Готова ли страница к разбору. Первый кадр страницы поиска — часто только шапка сайта
     * («Мои желания», «Вход / Регистрация», меню разделов). Если остановиться на ней, ссылок на
     * товары в разметке не будет, и поиск объявит блокировку там, где страница просто не
     * дорисовалась. Поэтому ждём либо ссылки на товары, либо явный вердикт поиска.
     */
    fun isReadyForExtraction(html: String?): Boolean {
        if (html.isNullOrBlank() || BotProtection.isChallengeHtml(html)) return false
        val lower = html.lowercase()
        return PRODUCT_HREF_REGEX.containsMatchIn(lower) || SEARCH_VERDICT_REGEX.containsMatchIn(lower)
    }

    /**
     * Сколько ссылок на товары видно в разметке. Нужен не для разбора, а для журнала: «0 ссылок»
     * и «страница-заглушка» — это разные поломки, и без счётчика их не отличить на расстоянии.
     */
    fun countProductLinks(html: String?): Int {
        if (html.isNullOrBlank()) return 0
        return PRODUCT_HREF_REGEX.findAll(html).count()
    }

    fun extractHits(html: String?, query: String, limit: Int = MAX_HITS): List<SiteSearchHit> {
        if (html.isNullOrBlank()) return emptyList()

        val document = Jsoup.parse(html, BASE)
        val normalizedQuery = cleanQuery(query).lowercase()
        val tokens = normalizedQuery.split(' ').filter { it.length >= 2 }
        val byUrl = LinkedHashMap<String, SiteSearchHit>()

        for (anchor in document.select("a[href]")) {
            if (anchor.isInPageChrome()) continue

            val url = anchor.productUrl() ?: continue
            val row = anchor.nearestRow()
            // Название — из карточки, а не из текста ссылки: ссылкой в Allure может быть кнопка
            // «Подробнее». Если в карточке имени нет (например, ссылка только на картинку),
            // остаётся старый путь: title/alt самой ссылки.
            val title = anchor.card(url).productTitle(url, tokens)
                .ifBlank { anchor.hitTitle(row) }
                .cleanText()
            if (title.length < 2 || NOISE_LINE_REGEX.containsMatchIn(title)) continue

            val prices = PriceNormalizer.extractPricesFromOfferText(row.text())
                .filter { PriceNormalizer.isReasonablePrice(it) }

            val hit = SiteSearchHit(
                title = title,
                url = url,
                price = prices.minOrNull(),
                volumeLabel = VOLUME_REGEX.find(row.text())?.groupValues?.get(1)?.let { "$it мл" },
                fromResultsBlock = row !== anchor || anchor.looksLikeResultBlock(),
            )

            val previous = byUrl[url]
            if (previous == null || hit.betterThan(previous)) byUrl[url] = hit
        }

        return byUrl.values
            .sortedWith(
                compareByDescending<SiteSearchHit> { relevance(it, tokens, normalizedQuery) }
                    .thenByDescending { it.fromResultsBlock }
                    .thenByDescending { it.price != null }
                    .thenBy { it.title.lowercase() },
            )
            .take(limit.coerceAtLeast(1))
    }

    /** Строка результата «лучше» другой, если у неё больше полезных деталей. */
    private fun SiteSearchHit.betterThan(other: SiteSearchHit): Boolean =
        (fromResultsBlock && !other.fromResultsBlock) ||
            (price != null && other.price == null) ||
            (price != null && price == other.price && title.length > other.title.length)

    private fun relevance(hit: SiteSearchHit, tokens: List<String>, query: String): Int {
        // « - » между брендом и названием вставляется нами, а не сайтом: при сравнении с запросом
        // её убираем, иначе точное совпадение с введённым названием не давало бы преимущества.
        val title = hit.title.lowercase().replace(" - ", " ")
        var score = if (hit.fromResultsBlock) 6 else 0
        if (query.isNotBlank() && title.contains(query)) score += 12
        score += tokens.count { title.contains(it) } * 4
        if (hit.price != null) score += 1
        return score
    }

    /**
     * Имя товара из блока-карточки: «Бренд - Товар».
     *
     * В выдаче Allure бренд и название — две соседние строки, а ссылкой обычно является кнопка
     * «Подробнее», поэтому текст ссылки для имени непригоден. Строки ищутся по всему блоку в
     * порядке разметки: первая — бренд, вторая — название. Если строка одна (название уже написано
     * вместе с брендом), она и становится именем.
     *
     * Блок может оказаться меньше карточки (например, ссылка только на картинку), поэтому позволяем
     * подняться на пару уровня вверх, но только пока родитель не начал включать чужие товары.
     */
    private fun Element.productTitle(url: String, tokens: List<String>): String {
        var node: Element? = this
        var depth = 0
        var fallback = ""
        while (node != null && depth < 3) {
            val lines = node.nameLines()
            if (lines.size >= 2) return brandNameOf(lines, tokens)
            if (fallback.isBlank()) fallback = lines.firstOrNull().orEmpty()

            // Выше идём только пока блок по-прежнему относится к одному товару: иначе имя
            // склеится из двух карточек, когда над ними висит общий список выдачи.
            val parent = node.parent() ?: break
            if (!parent.containsOnlyUrl(url)) break
            node = parent
            depth++
        }
        return fallback
    }

    /**
     * Текстовые строки блока, похожие на имя бренда или товара, в порядке разметки.
     *
     * Собственный текст элемента учитываем первым: карточкой может оказаться и сама ссылка
     * (`<a>Marc-Antoine Barrois Ganymede</a>`), у которой детей нет вовсе.
     */
    private fun Element.nameLines(): List<String> =
        (listOf(this) + select("*").toList())
            .asSequence()
            .map { it.ownText().stripVolumes().cleanText() }
            .filter { it.isNameLike() && !BADGE_LINE_REGEX.matches(it) }
            .distinct()
            .toList()

    /**
     * Из строк карточки название — второе, бренд — первое. Если вторая строка к запросу не имеет
     * отношения (в других шаблонах на её месте стоит подпись «туалетная вода»), название ищем среди
     * строк, которые запросу соответствуют: лучше показать «Бренд - Товар», чем «Бренд - Тип».
     */
    private fun brandNameOf(lines: List<String>, tokens: List<String>): String {
        val brand = lines.first()
        var name = lines.getOrElse(1) { brand }
        if (tokens.isNotEmpty() && tokens.none { name.contains(it, ignoreCase = true) }) {
            name = lines.drop(1).firstOrNull { line -> tokens.any { line.contains(it, ignoreCase = true) } }
                ?: name
        }
        return joinBrandAndName(brand, name)
    }

    private fun joinBrandAndName(brand: String, name: String): String = when {
        name.isBlank() -> brand
        name == brand || name.contains(brand, ignoreCase = true) -> name
        brand.length > MAX_BRAND_LENGTH -> name
        else -> "$brand - $name"
    }

    /** «Ganymede, 30 мл» — это название с объёмом, а не объём отдельно: последнее вырезаем. */
    private fun String.stripVolumes(): String = VOLUME_REGEX.replace(this, " ")

    private fun String.isNameLike(): Boolean {
        val text = trim()
        if (text.length !in 2..60) return false
        if (!LETTERS_REGEX.containsMatchIn(text)) return false
        if (PRICEISH_REGEX.containsMatchIn(text)) return false
        return !NOISE_LINE_REGEX.containsMatchIn(text)
    }

    /**
     * Карточка товара: ближайший предок ссылки, в котором есть цена и нет ссылок на чужие товары.
     * Нужна только для названия; цену и объём по-прежнему берём из [nearestRow], чтобы не начать
     * показывать диапазон всей выдачи вместо цены конкретного предложения.
     */
    private fun Element.card(url: String): Element {
        var node: Element? = parent()
        var depth = 0
        while (node != null && depth < 4) {
            if (node.text().length <= MAX_CARD_TEXT_LENGTH && node.containsOnlyUrl(url) && node.hasPriceText()) {
                return node
            }
            node = node.parent()
            depth++
        }
        return this
    }

    /** В блоке есть ссылка на [url] и нет ссылок на другие товары — значит, блок про один товар. */
    private fun Element.containsOnlyUrl(url: String): Boolean {
        var found = false
        for (link in select("a[href]")) {
            val target = link.productUrl() ?: continue
            if (target != url) return false
            found = true
        }
        return found
    }

    private fun Element.hasPriceText(): Boolean =
        PriceNormalizer.extractPricesFromOfferText(text()).isNotEmpty()

    /** Название строки выдачи: title ссылки, иначе alt картинки, иначе её текст, иначе текст блока. */
    private fun Element.hitTitle(row: Element): String {
        val fromAttr = attr("title").ifBlank { selectFirst("img[alt]")?.attr("alt").orEmpty() }
        val raw = fromAttr.ifBlank { text() }.cleanText()
        val candidate = raw.ifBlank { row.text().cleanText() }
        return if (candidate.length <= TITLE_MAX_LENGTH) {
            candidate
        } else {
            candidate.substringBefore(" | ").substringBefore(" \u2014 ").cleanText().take(TITLE_MAX_LENGTH)
        }
    }

    /**
     * Ближайший предок со ценой — это строка результата. Если выше идёт список целиком
     * (больше двух ссылок на товары), цену из него брать нельзя: она уедет на чужой товар.
     */
    private fun Element.nearestRow(): Element {
        var node: Element? = parent()
        var depth = 0
        while (node != null && depth < 4) {
            val current = node
            if (current.productLinkCount() > 2) return this
            if (current.hasPriceText()) return current
            node = current.parent()
            depth++
        }
        return this
    }

    private fun Element.productLinkCount(): Int = select("a[href]").count { it.productUrl() != null }

    /** Ссылка на страницу товара -> канонический URL безquery-параметров пейджинга. */
    private fun Element.productUrl(): String? {
        val absolute = absUrl("href").ifBlank { attr("href") }
        if (absolute.isBlank()) return null

        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host
        if (host != null && host != "allureparfum.ru" && !host.endsWith(".allureparfum.ru")) return null

        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        if (!path.startsWith("/katalog/")) return null
        if (!path.endsWith(".html") && !path.endsWith(".htm")) return null
        // Нужен как минимум /katalog/<раздел>/<товар>.html: страницы разделов ищем не здесь.
        if (path.count { it == '/' } < 3) return null

        return ProductUrlNormalizer.normalize(path)
    }

    private fun Element.isInPageChrome(): Boolean {
        var node: Element? = this
        while (node != null) {
            if (node.tagName().lowercase() in CHROME_TAGS) return true
            node = node.parent()
        }
        return false
    }

    private fun Element.looksLikeResultBlock(): Boolean {
        var node: Element? = parent()
        var depth = 0
        while (node != null && depth < 3) {
            val signature = "${node.className()} ${node.id()}"
            if (RESULT_BLOCK_REGEX.containsMatchIn(signature)) return true
            node = node.parent()
            depth++
        }
        return false
    }

    private fun cleanQuery(query: String): String = query.trim().replace(WHITESPACE_REGEX, " ").take(100)

    private fun String.cleanText(): String = WHITESPACE_REGEX.replace(replace('\u00A0', ' '), " ").trim()
}
