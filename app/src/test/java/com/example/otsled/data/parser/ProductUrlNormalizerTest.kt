package com.example.otsled.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductUrlNormalizerTest {
    @Test
    fun normalize_fullUrl_unchanged() {
        val url = "https://allureparfum.ru/katalog/zhenskaya-parfyumeriya/xerjoff/1861-naxos.html"
        assertEquals(url, ProductUrlNormalizer.normalize(url))
    }

    @Test
    fun normalize_relativePath_addsBase() {
        val input = "katalog/zhenskaya-parfyumeriya/xerjoff/1861-naxos.html"
        assertEquals(
            "https://allureparfum.ru/katalog/zhenskaya-parfyumeriya/xerjoff/1861-naxos.html",
            ProductUrlNormalizer.normalize(input),
        )
    }

    @Test
    fun isSupportedUrl_acceptsPartialPath() {
        assertTrue(
            ProductUrlNormalizer.isSupportedUrl(
                "katalog/zhenskaya-parfyumeriya/xerjoff/1861-naxos.html",
            ),
        )
    }
}
