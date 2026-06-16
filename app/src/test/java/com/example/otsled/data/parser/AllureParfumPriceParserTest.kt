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
    fun normalizePrice_handlesSpacesAndRub() {
        assertEquals(3420.0, PriceNormalizer.normalize("3 420 руб.")!!, 0.01)
    }
}
