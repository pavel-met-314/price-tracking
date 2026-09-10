package com.example.otsled.data.parser

import com.example.otsled.data.site.SiteSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Русский текст на страницах бывает в верхнем регистре: «4 200 РУБ.», «50 МЛ», «ПОДРОБНЕЕ»,
 * «УНИСЕКС». В java.util.regex флаг `(?i)` сворачивает регистр только для ASCII, поэтому такие
 * шаблоны обязывают `(?iu)` — иначе парсер молча перестаёт видеть цены и объёмы, а фильтр мусора
 * в выдаче перестаёт отбрасывать подписи блока. Эти тесты фиксируют именно равенство разбора
 * строк, отличающихся только регистром: ловят регрессию быстрее, чем «цены нет на телефоне».
 */
class CyrillicCaseParsingTest {
    private val parser = AllureParfumPriceParser()

    @Test
    fun offerRowInUpperCaseIsParsedLikeLowerCase() {
        val lower = """
            <html><head><meta property="og:title" content="Marc-Antoine Barrois Ganymede"/></head><body>
              <div class="offer-row">парфюмерная вода 50 мл. 4 200 руб. 5 100 руб.</div>
              <div class="offer-row">туалетная вода 100 мл. 9 900 руб.</div>
            </body></html>
        """.trimIndent()
        val upper = lower
            .replace("парфюмерная вода", "ПАРФЮМЕРНАЯ ВОДА")
            .replace("туалетная вода", "ТУАЛЕТНАЯ ВОДА")
            .replace(" мл.", " МЛ.")
            .replace(" руб.", " РУБ.")

        val fromLower = parser.parseHtml(lower) as ParseResult.Success
        val fromUpper = parser.parseHtml(upper) as ParseResult.Success

        assertEquals(fromLower.price, fromUpper.price, 0.01)
        assertEquals(fromLower.variants.map { it.volume to it.price }, fromUpper.variants.map { it.volume to it.price })
    }

    @Test
    fun articleAndVolumeInUpperCaseAreStillRecognized() {
        val lower = "Артикул 12345 парфюмерная вода 50 мл. 4 200 руб."
        val upper = "АРТИКУЛ 12345 ПАРФЮМЕРНАЯ ВОДА 50 МЛ. 4 200 РУБ."

        assertEquals(
            PriceNormalizer.extractPricesFromOfferText(lower),
            PriceNormalizer.extractPricesFromOfferText(upper),
        )
        assertEquals(4200.0, PriceNormalizer.normalize("4 200 РУБ.")!!, 0.01)
    }

    @Test
    fun challengeStubInUpperCaseIsStillAChallenge() {
        val upper = "<HTML><BODY><H1>ВЫПОЛНЯЕТСЯ ПРОВЕРКА ВАШЕГО ВЕБ-БРАУЗЕРА</H1></BODY></HTML>"

        assertTrue(BotProtection.isChallengeHtml(upper))
        // Текст выжимки остаётся как в исходнике — его просят прислать в журнал, менять нельзя.
        assertEquals("ВЫПОЛНЯЕТСЯ ПРОВЕРКА ВАШЕГО ВЕБ-БРАУЗЕРА", BotProtection.stubSummary(upper))
    }

    @Test
    fun searchResultInUpperCaseIsReadyAndCleanOfLabels() {
        val html = """
            <html><body><main><div class="item">
              <div class="stamp">ХИТ</div>
              <a href="/katalog/na_muzhchinye-arekate/ganymede-76733.html"><img src="/i/1.jpg"></a>
              <div class="info">
                <a href="/brend/marc-antoine-barrois/">MARC-ANTOINE BARROIS</a>
                <a href="/katalog/na_muzhchinye-arekate/ganymede-76733.html">GANYMEDE</a>
                <div class="price">360 - 22 235 РУБ.</div>
                <div>УНИСЕКС</div>
                <div>СЕМЕЙСТВО: ДРЕВЕСНЫЕ, ПРЯНЫЕ</div>
              </div>
              <a href="/katalog/na_muzhchinye-arekate/ganymede-76733.html">ПОДРОБНЕЕ</a>
            </div></main></body></html>
        """.trimIndent()

        assertTrue(SiteSearchQuery.isReadyForExtraction(html))
        assertFalse(BotProtection.isChallengeHtml(html))

        val hit = SiteSearchQuery.extractHits(html, "ganymede").single()

        assertEquals("MARC-ANTOINE BARROIS - GANYMEDE", hit.title)
        assertEquals(22_235.0, hit.price!!, 0.01)
    }
}
