package com.example.otsled.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессии, из-за которых трекер показывал не те цены: заглушка анти-бота вместо товара,
 * «цена за 1 мл» как отдельный объём, цены из блока «похожие ароматы» и перепутанные
 * старая/новая цена в одной строке.
 */
class PriceParserReliabilityTest {
    private val parser = AllureParfumPriceParser()

    @Test
    fun isChallengeHtml_detectsBrowserCheckStub() {
        val stub = """
            <!DOCTYPE html><html><head><title>Проверка браузера</title></head>
            <body><h1>Выполняется проверка вашего веб-браузера...</h1>
            <p>После проверки вы будете переведены на запрашиваемую веб-страницу автоматически.</p>
            </body></html>
        """.trimIndent()

        assertTrue(BotProtection.isChallengeHtml(stub))
    }

    @Test
    fun isChallengeHtml_ignoresPlainPageWithoutPrices() {
        val page = "<!DOCTYPE html><html><body><div>Наш магазин работает с 10 до 20</div></body></html>"

        assertFalse(BotProtection.isChallengeHtml(page))
    }

    @Test
    fun isChallengeHtml_ignoresRealProductMarkup() {
        val page = """
            <html><body><h1>Creed Aventus</h1><div>парфюмерная вода 50 мл — 4 200 руб.</div></body></html>
        """.trimIndent()

        assertFalse(BotProtection.isChallengeHtml(page))
    }

    @Test
    fun parseHtml_doesNotInventVolumeFromPricePerMl() {
        val html = """
            <html><head><meta property="og:title" content="Xerjoff Naxos"/></head><body>
              <div class="offer-row">парфюмерная вода 50 мл. 4 200 руб. Цена за 1 мл: 84 руб.</div>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html) as ParseResult.Success

        assertEquals(1, result.variants.size)
        assertEquals("50 мл", result.variants.first().volume)
        assertEquals(4200.0, result.variants.first().price, 0.01)
    }

    @Test
    fun parseHtml_ignoresSimilarProductsBlock() {
        val html = """
            <html><head><meta property="og:title" content="Creed Aventus"/></head><body>
              <div class="product-item-detail">
                <div class="offer-row">парфюмерная вода 50 мл. 4 200 руб.</div>
              </div>
              <div class="similar-products">
                <div class="offer-row">парфюмерная вода 100 мл. 9 900 руб.</div>
              </div>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html) as ParseResult.Success

        assertEquals(listOf("50 мл"), result.variants.map { it.volume })
        assertEquals(4200.0, result.price, 0.01)
    }

    @Test
    fun parseHtml_ignoresDeliveryPriceInsideOfferRow() {
        val html = """
            <html><head><meta property="og:title" content="Creed Aventus"/></head><body>
              <div class="offer-row">парфюмерная вода 50 мл. 4 200 руб. Доставка 300 руб.</div>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html) as ParseResult.Success
        val variant = result.variants.first { it.volume == "50 мл" }

        assertEquals(4200.0, variant.price, 0.01)
    }

    @Test
    fun parseHtml_picksLowerPriceAsCurrent_whenOldPriceComesFirst() {
        val html = """
            <html><head><meta property="og:title" content="Creed Aventus"/></head><body>
              <div class="offer-row">Артикул 12345 парфюмерная вода 50 мл. 5 100 руб. 4 200 руб.</div>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html) as ParseResult.Success
        val variant = result.variants.first { it.volume == "50 мл" }

        assertEquals(4200.0, variant.price, 0.01)
        assertEquals(5100.0, variant.oldPrice!!, 0.01)
    }

    @Test
    fun extractOfferPrices_keepsOffsets_afterStrippingArticleAndVolume() {
        val text = "Артикул 38668 парфюмерная вода 1 мл. 315 руб."
        val occurrences = PriceNormalizer.extractOfferPrices(text)

        assertEquals(1, occurrences.size)
        assertEquals(315.0, occurrences.first().value, 0.01)
        // Оффсет должен указывать на то же место в исходном тексте, что и в вычищенном.
        assertTrue(text.startsWith("315", occurrences.first().offset))
    }

    @Test
    fun priceNoise_detectsPerMlAndDelivery() {
        assertTrue(PriceNoise.isPricePerMl("Цена за 1 мл: 84 руб."))
        assertTrue(PriceNoise.hasNoiseKeyword("Бесплатная доставка от 3000 руб."))
        assertFalse(PriceNoise.hasNoiseKeyword("Парфюмерная вода 50 мл"))

        val row = "парфюмерная вода 50 мл. 4 200 руб. Доставка 300 руб."
        val prices = PriceNormalizer.extractOfferPrices(row)
        val filtered = prices.filterNot { PriceNoise.isPriceAttachedToNoise(row, it.offset) }

        assertEquals(listOf(4200.0), filtered.map { it.value })
    }

    @Test
    fun stripPricePerMl_removesBothFormats() {
        val spaced = PriceNoise.stripPricePerMl("парфюмерная вода 50 мл. 4 200 руб. Цена за 1 мл: 84 руб.")
        assertFalse(spaced.contains("84"))
        assertTrue(spaced.contains("4 200 руб"))
        assertTrue(spaced.contains("50 мл"))

        val slashed = PriceNoise.stripPricePerMl("100 мл 84 ₽/мл")
        assertFalse(slashed.contains("84"))
        assertTrue(slashed.contains("100 мл"))
    }
}
