package com.example.otsled.data.site

import com.example.otsled.data.parser.BotProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор выдачи поиска. Живой разметки страницы поиска у нас нет — сайт отвечает JS-проверкой
 * браузера, — поэтому фикстуры ниже намеренно «обобщённо-битриксные»: парсер не должен зависеть
 * от имён классов, и эти тесты фиксируют именно это.
 */
class SiteSearchQueryTest {

    private val ganymedeUrl =
        "https://allureparfum.ru/katalog/zhenskaya-parfyumeriya/marc-antoine-barrois/ganymede.html"

    private val searchResultsHtml = """
        <!DOCTYPE html><html><head><title>Поиск — Ganymede</title></head><body>
        <header><nav>
          <a href="/katalog/zhenskaya-parfyumeriya/creed/aventus.html">Creed Aventus</a>
        </nav></header>
        <main>
          <div class="search-result-item">
            <a href="/katalog/zhenskaya-parfyumeriya/marc-antoine-barrois/ganymede.html"
               title="Marc-Antoine Barrois Ganymede">Marc-Antoine Barrois Ganymede</a>
            <div class="props">30 мл, 50 мл</div>
            <div class="price">от 5 470 руб.</div>
          </div>
          <div class="search-result-item">
            <a href="/katalog/zhenskaya-parfyumeriya/marc-antoine-barrois/ganymede.html">
              <img src="/i/ganymede.jpg" alt="Marc-Antoine Barrois Ganymede">
            </a>
          </div>
          <div class="search-result-item">
            <a href="/katalog/muzhskaya-parfyumeriya/marc-antoine-barrois/ganymede-edt.html">
              Marc-Antoine Barrois Ganymede EDT
            </a>
            <div class="price">7 100 руб.</div>
          </div>
          <div class="search-result-item">
            <a href="/katalog/zhenskaya-parfyumeriya/marc-antoine-barrois/b336.html">
              Marc-Antoine Barrois B336
            </a>
            <div class="price">5 900 руб.</div>
          </div>
        </main>
        <ul class="breadcrumb"><li><a href="/katalog/zhenskaya-parfyumeriya/">Женская</a></li></ul>
        <a href="/brend/marc-antoine-barrois/">Marc-Antoine Barrois</a>
        <footer><a href="/katalog/zhenskaya-parfyumeriya/chanel/chance.html">Chanel Chance</a></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun productFromSearchResultsIsFoundWithExactUrl() {
        val hits = SiteSearchQuery.extractHits(searchResultsHtml, "Marc-Antoine Barrois Ganymede")

        val hit = hits.first()
        assertEquals(ganymedeUrl, hit.url)
        assertEquals("Marc-Antoine Barrois Ganymede", hit.title)
        assertEquals(5470.0, hit.price!!, 0.0)
        assertEquals("30 мл", hit.volumeLabel)
        assertTrue(hit.fromResultsBlock)
    }

    @Test
    fun navigationAndFooterLinksAreNotResults() {
        val urls = SiteSearchQuery.extractHits(searchResultsHtml, "ganymede").map { it.url }

        assertFalse(urls.any { it.contains("aventus") })
        assertFalse(urls.any { it.contains("chance") })
        assertFalse(urls.any { it.endsWith("/zhenskaya-parfyumeriya/") })
        assertFalse(urls.any { it.contains("/brend/") })
    }

    @Test
    fun relevancePutsExactNameFirst() {
        val hits = SiteSearchQuery.extractHits(searchResultsHtml, "ganymede")

        // B336 — тот же бренд, но не «ganymede». Такую строку сайт под результатами показывает в
        // блоке «похожие», и пользователю она в выдаче поиска не нужна.
        assertEquals(2, hits.size)
        assertEquals(ganymedeUrl, hits[0].url)
        assertTrue(hits.all { it.title.contains("ganymede", ignoreCase = true) })
    }

    @Test
    fun brandQueryKeepsAllProductsOfTheBrand() {
        val hits = SiteSearchQuery.extractHits(searchResultsHtml, "Marc-Antoine Barrois")

        // Поиск по бренду — это запрос про бренд: B336 подходит, «не по запросу» тут ничего нет.
        assertTrue(hits.any { it.url.endsWith("b336.html") })
        assertEquals(0, SiteSearchQuery.extractDetailed(searchResultsHtml, "Marc-Antoine Barrois").offTopic)
    }

    @Test
    fun duplicateLinksMergeIntoOneHit() {
        val hits = SiteSearchQuery.extractHits(searchResultsHtml, "ganymede")

        assertEquals(1, hits.count { it.url == ganymedeUrl })
        // Картинка сама по себе без цены, но сливается с текстовой ссылкой, а не дублирует строку.
        assertEquals(5470.0, hits.first { it.url == ganymedeUrl }.price!!, 0.0)
    }

    @Test
    fun pagingParametersAreDroppedFromUrl() {
        val html = """
            <main><div class="search-item">
              <a href="/katalog/zhenskaya-parfyumeriya/x/ganymede.html?PAGEN_1=2">Ganymede</a>
            </div></main>
        """.trimIndent()

        val hit = SiteSearchQuery.extractHits(html, "ganymede").single()

        assertEquals("https://allureparfum.ru/katalog/zhenskaya-parfyumeriya/x/ganymede.html", hit.url)
        assertNull(hit.price)
        // Цены в блоке нет, но контейнер называется search-item — строка всё равно результативная.
        assertTrue(hit.fromResultsBlock)
    }

    @Test
    fun bareProductLinksStillWorkWhenLayoutIsUnknown() {
        val html = """
            <html><body><section class="anything-else"><p>
              <a href="/katalog/nishe/nisan/nisi.html">Nisan Nisi</a>
            </p></section></body></html>
        """.trimIndent()

        val hit = SiteSearchQuery.extractHits(html, "nisi").single()

        assertEquals("https://allureparfum.ru/katalog/nishe/nisan/nisi.html", hit.url)
        assertEquals("Nisan Nisi", hit.title)
    }

    @Test
    fun productLinksAreCountedForDiagnostics() {
        // Счётчик сырой: он считает ссылки в разметке, а «меню/подвал» отсекает уже разбор строк.
        assertEquals(6, SiteSearchQuery.countProductLinks(searchResultsHtml))
        assertEquals(0, SiteSearchQuery.countProductLinks("<html><body><a href=\"/brend/x/\">x</a></body></html>"))
        assertEquals(0, SiteSearchQuery.countProductLinks(null))
    }

    @Test
    fun challengePageHasNothingToParse() {
        val html = """
            <html><head><title>Проверка браузера</title></head><body>
            <div class="js-challenge-script">Выполняется проверка вашего веб-браузера…</div>
            </body></html>
        """.trimIndent()

        assertTrue(BotProtection.isChallengeHtml(html))
        assertTrue(SiteSearchQuery.extractHits(html, "ganymede").isEmpty())
        assertEquals(0, SiteSearchQuery.countProductLinks(html))
    }

    @Test
    fun searchUrlCarriesBitrixSearchParameters() {
        assertEquals(
            "https://allureparfum.ru/search/?q=Ganymede&s_search=Y&how=r",
            SiteSearchQuery.searchUrl("Ganymede"),
        )
        assertTrue(SiteSearchQuery.searchUrl("Marc Antoine Barrois").contains("q=Marc+Antoine+Barrois"))
        assertTrue(SiteSearchQuery.searchUrl("  ганнимед  ").contains("q=%D0%B3%D0%B0%D0%BD%D0%BD%D0%B8%D0%BC%D0%B5%D0%B4"))
    }

    @Test
    fun shortQueriesRejected() {
        assertTrue(SiteSearchQuery.isQueryTooShort("ab"))
        assertTrue(SiteSearchQuery.isQueryTooShort("   "))
        assertFalse(SiteSearchQuery.isQueryTooShort("Ganymede"))
    }

    @Test
    fun emptyHtmlYieldsNoHits() {
        assertTrue(SiteSearchQuery.extractHits(null, "ganymede").isEmpty())
        assertTrue(SiteSearchQuery.extractHits("<html><body>нет товаров</body></html>", "ganymede").isEmpty())
    }

    /**
     * То, из-за чего поиск выглядел сломанным: под результатами страница отдаёт карусель
     * «Вы смотрели» с точно такими же ссылками на `/katalog/…`. Отслеживаемых товаров много — и
     * все они лезут в выдачу на любой запрос.
     */
    private val recentlyViewedHtml = """
        <!DOCTYPE html><html><body><main>
        <h1>Результаты поиска «kirke»</h1>
        <div class="search-result">
          <div class="item">
            <a href="/katalog/zhenskaya-parfyumeriya/tiziana-terenzi/kirke-70001.html"><img src="/i/1.jpg" alt="Tiziana Terenzi Kirke"></a>
            <div class="info">
              <a href="/brend/tiziana-terenzi/">Tiziana Terenzi</a>
              <a href="/katalog/zhenskaya-parfyumeriya/tiziana-terenzi/kirke-70001.html">Kirke</a>
              <div class="price">10 885 руб.</div>
            </div>
            <a href="/katalog/zhenskaya-parfyumeriya/tiziana-terenzi/kirke-70001.html">Подробнее</a>
          </div>
        </div>
        <section class="slider"><h2>Вы смотрели</h2>
          <div class="item">
            <a href="/katalog/na_muzhchinye-arekate/xerjoff-1861-naxos-502.html"><img src="/i/2.jpg" alt="Xerjoff 1861 Naxos"></a>
            <div class="info">
              <a href="/brend/xerjoff/">Xerjoff</a>
              <a href="/katalog/na_muzhchinye-arekate/xerjoff-1861-naxos-502.html">1861 Naxos</a>
              <div class="price">335 руб.</div>
            </div>
            <a href="/katalog/na_muzhchinye-arekate/xerjoff-1861-naxos-502.html">Подробнее</a>
          </div>
          <div class="item">
            <a href="/katalog/zhenskaya-parfyumeriya/paco-rabanne/black-xs-for-her-777.html"><img src="/i/3.jpg" alt="Paco Rabanne Black XS for Her"></a>
            <div class="info">
              <a href="/brend/paco-rabanne/">Paco Rabanne</a>
              <a href="/katalog/zhenskaya-parfyumeriya/paco-rabanne/black-xs-for-her-777.html">Black XS for Her</a>
              <div class="price">220 руб.</div>
            </div>
            <a href="/katalog/zhenskaya-parfyumeriya/paco-rabanne/black-xs-for-her-777.html">Подробнее</a>
          </div>
        </section>
        </main></body></html>
    """.trimIndent()

    @Test
    fun recentlyViewedProductsDoNotJoinTheResults() {
        val extraction = SiteSearchQuery.extractDetailed(recentlyViewedHtml, "kirke")

        assertEquals(1, extraction.hits.size)
        assertEquals("Tiziana Terenzi - Kirke", extraction.hits[0].title)
        assertEquals(10885.0, extraction.hits[0].price!!, 0.01)
        assertEquals(2, extraction.offTopic)
        assertEquals(3, extraction.seen)
        assertEquals("отсеяно 2 строк не по запросу (в выдаче 3)", extraction.filterNote())
    }

    @Test
    fun nothingIsDroppedWhenQueryMatchesNoTitles() {
        // Поиск по артикулу: в названиях таких цифр нет. Оставить всё — правильное решение:
        // «ничего не найдено» из-за собственного фильтра было бы хуже лишней строки.
        val extraction = SiteSearchQuery.extractDetailed(recentlyViewedHtml, "76733")

        assertEquals(3, extraction.hits.size)
        assertEquals(0, extraction.offTopic)
        assertEquals("", extraction.filterNote())
    }

    /**
     * Разметка карточки выдачи, восстановленная по живому скриншоту сайта: ссылкой является кнопка
     * «Подробнее», а бренд и название товара — отдельные строки. Если название начать брать из текста
     * ссылки, в списке вместо товара стоит «подробнее» — на этом поиск и провалили.
     */
    private val allureLikeResultsHtml = """
        <!DOCTYPE html><html><body><main>
        <h1>Результаты поиска «ganymede» — найдено 3 товара:</h1>
        <div class="search-result">
          <div class="item">
            <div class="stamp">ХИТ</div>
            <a href="/katalog/na_muzhchinye-arekate/marc-antoine-barrois-ganymede-76733.html">
              <img src="/i/g1.jpg" alt="Marc-Antoine Barrois Ganymede">
            </a>
            <a href="/brend/marc-antoine-barrois/">Marc-Antoine Barrois</a>
            <a href="/katalog/na_muzhchinye-arekate/marc-antoine-barrois-ganymede-76733.html">Ganymede</a>
            <div class="price"><span>360</span> - <b>22 235</b> руб.</div>
            <div class="gender">Унисекс</div>
            <div>Семейство: древесные, пряные</div>
            <a href="/katalog/na_muzhchinye-arekate/marc-antoine-barrois-ganymede-76733.html">Подробнее</a>
            <a href="/quick/76733">Быстрый просмотр</a>
          </div>
          <div class="item">
            <div class="stamp">NEW</div>
            <a href="/katalog/na_muzhchinye-arekate/rabdan-ganymede-90001.html"><img src="/i/g3.jpg"></a>
            <div class="titles">
              <span class="brand">Rabdan</span>
              <a href="/katalog/na_muzhchinye-arekate/rabdan-ganymede-90001.html">Ganymede</a>
            </div>
            <div class="price">485 - 11 950 руб.</div>
            <div class="gender">Унисекс</div>
            <a href="/katalog/na_muzhchinye-arekate/rabdan-ganymede-90001.html">Подробнее</a>
          </div>
        </div>
        </main></body></html>
    """.trimIndent()

    @Test
    fun `card title is brand plus product name, not the button label`() {
        val titles = SiteSearchQuery.extractHits(allureLikeResultsHtml, "ganymede")
            .associate { it.url to it.title }

        assertEquals(
            "Marc-Antoine Barrois - Ganymede",
            titles["https://allureparfum.ru/katalog/na_muzhchinye-arekate/marc-antoine-barrois-ganymede-76733.html"],
        )
        assertEquals(
            "Rabdan - Ganymede",
            titles["https://allureparfum.ru/katalog/na_muzhchinye-arekate/rabdan-ganymede-90001.html"],
        )
        // Кнопка «Подробнее» не должна попадать в названия — ни целиком, ни в чьём-либо хвосте.
        assertTrue(titles.values.none { it.contains("подробн", ignoreCase = true) })
        assertEquals(2, titles.size)
    }

    @Test
    fun `descriptions and prices of the card are not mistaken for the name`() {
        val titles = SiteSearchQuery.extractHits(allureLikeResultsHtml, "ganymede").map { it.title }

        assertTrue(titles.all { !it.contains("руб", ignoreCase = true) })
        assertTrue(titles.all { !it.contains("Унисекс", ignoreCase = true) })
        assertTrue(titles.all { !it.contains("Семейство", ignoreCase = true) })
        assertTrue(titles.all { !it.contains("ХИТ", ignoreCase = true) })
    }

    @Test
    fun `single name line is kept as is`() {
        // Карточка, где бренд уже написан внутри названия: второй строки нет, придумывать её нельзя.
        val html = """
            <html><body><div class="item">
              <a href="/katalog/razdel/brand/ganymede.html">Marc-Antoine Barrois Ganymede Extrait</a>
              <div class="price">7 900 руб.</div>
              <a href="/katalog/razdel/brand/ganymede.html">Подробнее</a>
            </div></body></html>
        """.trimIndent()

        val hit = SiteSearchQuery.extractHits(html, "ganymede").single()
        assertEquals("Marc-Antoine Barrois Ganymede Extrait", hit.title)
    }

    @Test
    fun `header-only page is not ready for extraction`() {
        // Первый кадр страницы: отрисована шапка, результатов ещё нет. Если принять её за готовую,
        // поиск отдаст «ничего не найдено» там, где страница просто не дорисовалась.
        val html = """
            <html><head><script src="https://api-site.com/captcha.js"></script></head>
            <body><div>0 Мои желания Вход / Регистрация Главная Бренды Доставка Оплата</div></body></html>
        """.trimIndent()
        assertFalse(SiteSearchQuery.isReadyForExtraction(html))
    }

    @Test
    fun `page with product links is ready for extraction`() {
        val html = """
            <html><body>
            <a href="/katalog/na_muzhchinye-arekate/ganymede-deo-parfum-76734.html">Ganymede</a>
            </body></html>
        """.trimIndent()
        assertTrue(SiteSearchQuery.isReadyForExtraction(html))
    }

    @Test
    fun `search verdict without results counts as ready`() {
        val html = "<html><body><h1>Поиск: ничего не найдено</h1></body></html>"
        assertTrue(SiteSearchQuery.isReadyForExtraction(html))
    }

    @Test
    fun `blank and challenge pages are not ready`() {
        assertFalse(SiteSearchQuery.isReadyForExtraction(null))
        assertFalse(SiteSearchQuery.isReadyForExtraction("   "))
        val stub = """
            <html><head><title>Доступ ограничен</title></head>
            <body><p>Check <b>captcha</b> field.</p></body></html>
        """.trimIndent()
        assertFalse(SiteSearchQuery.isReadyForExtraction(stub))
    }
}
