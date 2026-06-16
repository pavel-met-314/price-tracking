package com.example.otsled.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllureParfumPriceParserTest {
    private val parser = AllureParfumPriceParser()

    @Test
    fun parseHtml_extractsTitleAndPrice_fromItemprop() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Creed Aventus"/>
              </head>
              <body>
                <h1>Creed Aventus</h1>
                <span itemprop="price" content="560">560 руб.</span>
              </body>
            </html>
        """.trimIndent()

        val result = parser.parseHtml(html)

        assertTrue(result is ParseResult.Success)
        result as ParseResult.Success
        assertEquals("Creed Aventus", result.title)
        assertEquals(560.0, result.price, 0.01)
        assertEquals(1, result.variants.size)
    }

    @Test
    fun parseHtml_extractsMinimumPrice_fromRange() {
        val html = """
            <html><body>
              <h1>Tom Ford Lost Cherry</h1>
              <div class="product-item-price-current">700 - 124 475 руб.</div>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html)

        assertTrue(result is ParseResult.Success)
        result as ParseResult.Success
        assertEquals(700.0, result.price, 0.01)
    }

    @Test
    fun parseHtml_extractsAllVolumePrices_fromOfferTable() {
        val html = """
            <html><head>
              <meta property="og:title" content="Xerjoff 1861 Naxos"/>
            </head><body>
              <h1>Xerjoff 1861 Naxos</h1>
              <table>
                <tbody>
                  <tr>
                    <td>парфюмерная вода (пробник) Артикул 38668</td>
                    <td>1 мл.</td>
                    <td><span class="product-item-detail-price-current">315 руб.</span></td>
                  </tr>
                  <tr>
                    <td>парфюмерная вода (пробник) Артикул 69380</td>
                    <td>2 мл.</td>
                    <td><span class="product-item-detail-price-current">550 руб.</span></td>
                  </tr>
                  <tr>
                    <td>парфюмерная вода (уценка) Артикул 88712</td>
                    <td>100 мл.</td>
                    <td><span class="product-item-detail-price-current">13 550 руб.</span></td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html)

        assertTrue(result is ParseResult.Success)
        result as ParseResult.Success
        assertEquals(3, result.variants.size)
        assertEquals(315.0, result.variants.first { it.volume == "1 мл" }.price, 0.01)
        assertEquals(550.0, result.variants.first { it.volume == "2 мл" }.price, 0.01)
        assertEquals("1 мл — 315 ₽", result.variants.first { it.volume == "1 мл" }.priceLine())
    }

    @Test
    fun parseHtml_extractsVolumeAnchors_fromSeparateCells() {
        val html = """
            <html><body>
              <h1>Test</h1>
              <div class="offer-row">
                <span>3 мл.</span>
                <span class="product-item-detail-price-current">780 руб.</span>
              </div>
              <div class="offer-row">
                <span>5 мл.</span>
                <span class="product-item-detail-price-current">1 205 руб.</span>
              </div>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html)

        assertTrue(result is ParseResult.Success)
        result as ParseResult.Success
        assertEquals(2, result.variants.size)
    }

    @Test
    fun parseHtml_extractsPrice_fromJsonLd() {
        val html = """
            <html><head>
              <script type="application/ld+json">
                {"@type":"Product","name":"Test","offers":{"price":"1990.00"}}
              </script>
            </head><body><h1>Test</h1></body></html>
        """.trimIndent()

        val result = parser.parseHtml(html)

        assertTrue(result is ParseResult.Success)
        result as ParseResult.Success
        assertEquals(1990.0, result.price, 0.01)
    }

    @Test
    fun isSupportedUrl_acceptsAllureParfumHost() {
        assertTrue(parser.isSupportedUrl("https://allureparfum.ru/katalog/creed/aventus.html"))
    }

    @Test
    fun parseHtml_extractsPrice_fromAllureParfumLikeLayout() {
        val html = """
            <html><head>
              <meta property="og:title" content="Creed Aventus"/>
            </head><body>
              <h1 class="product-item-detail-title">Creed Aventus</h1>
              <div class="product-item-detail-price-current" id="price_value">560 руб.</div>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html, "https://allureparfum.ru/katalog/muzhskaya-parfyumeriya/creed/aventus.html")

        assertTrue(result is ParseResult.Success)
        result as ParseResult.Success
        assertEquals("Creed Aventus", result.title)
        assertEquals(560.0, result.price, 0.01)
    }

    @Test
    fun extractPricesFromOfferText_ignoresArticleNumber() {
        val text = "парфюмерная вода (пробник) Артикул 38668 1 мл. 315 руб. 355 руб."
        val prices = PriceNormalizer.extractPricesFromOfferText(text)
        assertEquals(315.0, prices[0], 0.01)
        assertEquals(355.0, prices[1], 0.01)
    }

    @Test
    fun parseHtml_doesNotMergeArticleWithPrice() {
        val html = """
            <html><body>
              <h1>Test</h1>
              <table><tbody><tr>
                <td>Артикул 38668 парфюмерная вода 1 мл.</td>
                <td>315 руб.</td>
              </tr></tbody></table>
            </body></html>
        """.trimIndent()

        val result = parser.parseHtml(html)

        assertTrue(result is ParseResult.Success)
        result as ParseResult.Success
        assertEquals(315.0, result.variants.first { it.volume == "1 мл" }.price, 0.01)
    }

    @Test
    fun normalizePrice_handlesSpacesAndRub() {
        assertEquals(3420.0, PriceNormalizer.normalize("3 420 руб.")!!, 0.01)
    }
}
