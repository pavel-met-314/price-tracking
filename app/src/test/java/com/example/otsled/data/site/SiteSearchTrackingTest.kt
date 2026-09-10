package com.example.otsled.data.site

import com.example.otsled.domain.model.TrackedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Умение поиска отличать «этот товар уже отслеживается» от «такого нет». Ошибка здесь дорогая:
 * вторая запись на ту же страницу товара выглядит как дубль, а вставка с `REPLACE` при этом тихо
 * стирает целевую цену и настройки уведомлений — поэтому сравнивать надо по каноническому URL,
 * а не по строке из href.
 */
class SiteSearchTrackingTest {

    private fun product(id: Long, url: String) = TrackedProduct(
        id = id,
        url = url,
        title = "Marc-Antoine Barrois Ganymede",
        targetPrice = null,
        lastPrice = 5470.0,
        lastCheckedAt = 1L,
    )

    private fun hit(url: String) = SiteSearchHit(title = "Marc-Antoine Barrois - Ganymede", url = url)

    private val page = "https://allureparfum.ru/katalog/na_muzhchinye-arekate/ganymede-76733.html"

    @Test
    fun exactUrlIsRecognized() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(listOf(product(7, page)))

        assertEquals(7L, SiteSearchTracking.trackedId(hit(page), tracked)!!)
    }

    @Test
    fun pagingAndFragmentParametersDoNotHideTheSamePage() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(
            listOf(product(9, "$page?PAGEN_1=2"), product(10, "$page#reviews")),
        )

        assertEquals(9L, SiteSearchTracking.trackedId(hit(page), tracked)!!)
    }

    @Test
    fun relativeUrlInDatabaseMatchesAbsoluteHitUrl() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(
            listOf(product(11, "/katalog/na_muzhchinye-arekate/ganymede-76733.html")),
        )

        assertEquals(11L, SiteSearchTracking.trackedId(hit(page), tracked)!!)
    }

    @Test
    fun anotherProductIsNotTracked() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(listOf(product(7, page)))

        assertNull(SiteSearchTracking.trackedId(hit("https://allureparfum.ru/katalog/creed/aventus.html"), tracked))
    }

    @Test
    fun emptyListMeansNothingIsTracked() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(emptyList())

        assertNull(SiteSearchTracking.trackedId(hit(page), tracked))
    }

    @Test
    fun resolveKeysByHitUrl() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(listOf(product(7, page)))
        val hits = listOf(hit(page), hit("https://allureparfum.ru/katalog/creed/aventus.html"))

        val resolved = SiteSearchTracking.resolve(hits, tracked)

        assertEquals(mapOf(page to 7L), resolved)
    }

    @Test
    fun reportCountsMatchesAndNamesBothSidesWhenNothingMatched() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(listOf(product(7, "https://allureparfum.ru/katalog/xerjoff/naxos.html")))
        val hits = listOf(hit(page), hit("$page?PAGEN_1=2"))

        val report = SiteSearchTracking.report(hits, tracked)

        // Счёт совпадений — то, по чему «пометки нет» различается с «такого товара нет».
        assertTrue(report.startsWith("совпало со списком 0 из 2 (в списке 1)"))
        assertTrue(report.contains("в выдаче $page"))
        assertTrue(report.contains("в базе https://allureparfum.ru/katalog/xerjoff/naxos.html"))
    }

    @Test
    fun reportStaysQuietWhenEverythingMatchedOrNothingSearched() {
        val tracked = SiteSearchTracking.trackedIdsByUrl(listOf(product(7, page)))

        assertEquals("совпало со списком 1 из 1 (в списке 1)", SiteSearchTracking.report(listOf(hit(page)), tracked))
        assertEquals("", SiteSearchTracking.report(emptyList(), tracked))
    }

    @Test
    fun duplicatePagesKeepTheFirstProduct() {
        // Защита от «две записи на один товар» — сама ситуация возможна из старых версий: важнее
        // не потерять первую (у неё история цен), чем указывать на обе сразу.
        val tracked = SiteSearchTracking.trackedIdsByUrl(listOf(product(3, page), product(4, "$page?PAGEN_1=1")))

        assertEquals(3L, SiteSearchTracking.trackedId(hit(page), tracked)!!)
    }
}
