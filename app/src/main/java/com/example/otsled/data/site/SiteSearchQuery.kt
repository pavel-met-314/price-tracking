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
 */
object SiteSearchQuery {

    const val BASE = "https://allureparfum.ru"

    private const val MAX_HITS = 24
    private const val MIN_QUERY_LENGTH = 3
    private const val TITLE_MAX_LENGTH = 160

    /** Ссылка на страницу товара прямо в HTML — по ней понимаем, что страница готова к разбору. */
    private val PRODUCT_HREF_REGEX = Regex("""/katalog/[^"'\s>]+\.html""", RegexOption.IGNORE_CASE)
    private val VOLUME_REGEX = Regex("""(\d+(?:[.,]\d+)?)\s*мл\.?""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val RESULT_BLOCK_REGEX = Regex("""(search|result|offer|item|product|card)""", RegexOption.IGNORE_CASE)
    /** Вердикт страницы поиска без результатов: ждать появления товаров бессмысленно. */
    private val SEARCH_VERDICT_REGEX = Regex(
        "(ничего не найдено|нет результатов|поиск не дал|совпадений не найдено|nothing found)",
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
     * Сколько ссылок на товары видно в разметке. Нужен не для разбора, а для журнала: «0 ссылок»
     * и «страница-заглушка» — это разные поломки, и без счётчика их не отличить на расстоянии.
     */
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
            val title = anchor.hitTitle(row)
            if (title.length < 2) continue

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
        val title = hit.title.lowercase()
        var score = if (hit.fromResultsBlock) 6 else 0
        if (query.isNotBlank() && title.contains(query)) score += 12
        score += tokens.count { title.contains(it) } * 4
        if (hit.price != null) score += 1
        return score
    }

    /** Название строки выдачи: title ссылки, иначе alt картинки, иначе её текст, иначе текст блока. */
    private fun Element.hitTitle(row: Element): String {
        val fromAttr = attr("title").ifBlank { selectFirst("img[alt]")?.attr("alt").orEmpty() }
        val raw = fromAttr.ifBlank { text() }.cleanText()
        val candidate = raw.ifBlank { row.text().cleanText() }
        return if (candidate.length <= TITLE_MAX_LENGTH) {
            candidate
        } else {
            candidate.substringBefore(" | ").substringBefore(" — ").cleanText().take(TITLE_MAX_LENGTH)
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
            if (PriceNormalizer.extractPricesFromOfferText(current.text()).isNotEmpty()) return current
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
