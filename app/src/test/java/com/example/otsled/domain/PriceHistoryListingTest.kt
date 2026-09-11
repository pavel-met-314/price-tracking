package com.example.otsled.domain

import com.example.otsled.domain.model.PriceHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила списка «История цен». Появились после того, как список фильтровался выбранным объёмом
 * графика: в карточке это выглядело как «история есть только у 1 мл», хотя записей было семь.
 * График остаётся про один объём, список — про все, и этот тест держит именно границу между ними.
 */
class PriceHistoryListingTest {

    private fun entry(
        variantId: Long?,
        price: Double,
        checkedAt: Long,
        label: String? = variantId?.let { "$it мл" },
    ) = PriceHistoryEntry(
        id = checkedAt * 10 + (variantId ?: 0L),
        productId = 1L,
        variantId = variantId,
        volumeLabel = label,
        price = price,
        checkedAt = checkedAt,
    )

    @Test
    fun allVolumesAreListedNotOnlyTheChartedOne() {
        val history = listOf(
            entry(variantId = 1L, price = 400.0, checkedAt = 10L),
            entry(variantId = 2L, price = 1_200.0, checkedAt = 11L),
            entry(variantId = 3L, price = 19_065.0, checkedAt = 12L),
        )

        val listing = PriceHistoryListing.rows(history)

        assertEquals(3, listing.rows.size)
        assertEquals(listOf(12L, 11L, 10L), listing.rows.map { it.checkedAt })
        assertEquals(0, listing.hiddenCount)
    }

    @Test
    fun rowsWithoutVariantAreKept() {
        // Строки до появления вариантов (id = null) — тоже история товара, выбросить их значит
        // потерять часть графика, который человек помнит.
        val listing = PriceHistoryListing.rows(listOf(entry(variantId = null, price = 300.0, checkedAt = 5L)))

        assertEquals(1, listing.rows.size)
        assertEquals(null, listing.rows.single().volumeLabel)
    }

    @Test
    fun emptyHistoryIsEmptyListing() {
        val listing = PriceHistoryListing.rows(emptyList())

        assertTrue(listing.isEmpty)
        assertEquals(0, listing.hiddenCount)
    }

    @Test
    fun limitReportsWhatItHid() {
        val history = (1L..250L).map { entry(variantId = 1L, price = it.toDouble(), checkedAt = it) }

        val listing = PriceHistoryListing.rows(history, limit = 200)

        assertEquals(200, listing.rows.size)
        assertEquals("скрытое надо показать подписью, а не молча обрезать", 50, listing.hiddenCount)
        assertEquals(250L, listing.rows.first().checkedAt)
        assertEquals(51L, listing.rows.last().checkedAt)
    }

    @Test
    fun sameMomentKeepsVolumeOrder() {
        // Один прогон пишет несколько объёмов с одной меткой времени: порядок внутри них обязан
        // быть предсказуемым, иначе список прыгает между обновлениями.
        val history = listOf(
            entry(variantId = 1L, price = 1_000.0, checkedAt = 7L, label = "100 мл"),
            entry(variantId = 2L, price = 300.0, checkedAt = 7L, label = "10 мл"),
        )

        val listing = PriceHistoryListing.rows(history)

        assertEquals(listOf("10 мл", "100 мл"), listing.rows.map { it.volumeLabel })
    }

    @Test
    fun zeroLimitDoesNotHideEverything() {
        val listing = PriceHistoryListing.rows(listOf(entry(1L, 1.0, 1L)), limit = 0)

        assertEquals(1, listing.rows.size)
    }
}
