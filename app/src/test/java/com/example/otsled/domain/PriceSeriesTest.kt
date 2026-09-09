package com.example.otsled.domain

import com.example.otsled.domain.model.PriceHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ряд цен кормит и график, и подписи «мин/макс/дельта». Ошибка здесь выглядит как правдоподобная
 * цифра, поэтому каждая проверка ниже — про конкретный способ соврать пользователю.
 */
class PriceSeriesTest {

    private fun history(
        vararg pricesByTime: Pair<Long, Double>,
        variantId: Long? = null,
        productId: Long = 1L,
    ): List<PriceHistoryEntry> = pricesByTime.map { (time, price) ->
        PriceHistoryEntry(
            id = time,
            productId = productId,
            variantId = variantId,
            price = price,
            checkedAt = time,
        )
    }

    @Test
    fun emptyHistoryHasNoTrend() {
        val series = buildPriceSeries(emptyList())
        assertEquals(PriceSeries.EMPTY, series)
        assertFalse(series.hasTrend)
        assertNull(series.deltaFromPrevious)
    }

    @Test
    fun pointsSortedByTime() {
        val series = buildPriceSeries(
            listOf(
                PricePoint(30L, 900.0),
                PricePoint(10L, 1000.0),
                PricePoint(20L, 950.0),
            ),
        )
        assertEquals(listOf(10L, 20L, 30L), series.points.map { it.checkedAt })
    }

    @Test
    fun oneCheckMergesAllVolumesToLowestPrice() {
        val series = buildPriceSeries(
            listOf(
                PricePoint(10L, 8000.0),
                PricePoint(10L, 5000.0),
                PricePoint(20L, 7000.0),
            ),
        )
        assertEquals(2, series.points.size)
        assertEquals(5000.0, series.points.first().price, 0.0)
    }

    @Test
    fun deltaFromPreviousTakesLastTwoPoints() {
        val series = buildPriceSeries(
            listOf(
                PricePoint(10L, 1000.0),
                PricePoint(20L, 900.0),
                PricePoint(30L, 950.0),
            ),
        )
        assertEquals(50.0, series.deltaFromPrevious!!, 0.0)
        assertEquals(-50.0, series.deltaFromFirst!!, 0.0)
        assertFalse(series.isFalling)
    }

    @Test
    fun fallingPriceIsReported() {
        val series = buildPriceSeries(
            listOf(
                PricePoint(10L, 1000.0),
                PricePoint(20L, 900.0),
            ),
        )
        assertTrue(series.isFalling)
        assertEquals(-100.0, series.deltaFromPrevious!!, 0.0)
    }

    @Test
    fun singlePointHasNoPrevious() {
        val series = buildPriceSeries(listOf(PricePoint(10L, 1000.0)))
        assertFalse(series.hasTrend)
        assertNull(series.previous)
        assertNull(series.deltaFromPrevious)
        assertNull(series.deltaFromFirst)
        assertEquals(1000.0, series.min!!.price, 0.0)
        assertEquals(series.min, series.max)
        assertEquals(1000.0, series.average!!, 0.0)
    }

    @Test
    fun extremesAndAverage() {
        val series = buildPriceSeries(
            listOf(
                PricePoint(10L, 1200.0),
                PricePoint(20L, 800.0),
                PricePoint(30L, 1600.0),
            ),
        )
        assertEquals(800.0, series.min!!.price, 0.0)
        assertEquals(1600.0, series.max!!.price, 0.0)
        assertEquals(1200.0, series.average!!, 0.0)
    }

    @Test
    fun oldestPointsAreDroppedAndReported() {
        val points = (0 until 150).map { PricePoint(it * 10L, if (it == 0) 1.0 else 500.0 + it) }
        val series = buildPriceSeries(points)
        assertEquals(PriceSeries.MAX_POINTS, series.points.size)
        assertEquals(30, series.droppedPoints)
        // Дешёвая точка вне окна не должна попадать в «минимум» — иначе подпись врёт.
        assertEquals(500.0 + 30, series.min!!.price, 0.0)
    }

    @Test
    fun historyFilteredByVariant() {
        val entries = history(10L to 5000.0, 20L to 4800.0, variantId = 1L) +
            history(10L to 9000.0, 20L to 8500.0, variantId = 2L)

        val both = buildPriceSeriesFromHistory(entries)
        assertEquals(4800.0, both.min!!.price, 0.0)

        val onlyFifty = buildPriceSeriesFromHistory(entries, variantId = 2L)
        assertEquals(2, onlyFifty.points.size)
        assertEquals(8500.0, onlyFifty.current!!.price, 0.0)
        assertEquals(-500.0, onlyFifty.deltaFromPrevious!!, 0.0)
    }
}
