package com.example.otsled.domain

import com.example.otsled.domain.model.PriceHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пометка «цена менялась» в списке и порядок «упали сильнее всего» считаются отсюда. Оба строки
 * строятся на одном правиле: сравнивать можно только один и тот же объём, иначе список врёт —
 * на смешении 2 мл и 100 мл мини-график показывал рост там, где цена падала.
 */
class VariantPriceChangesTest {

    private fun entry(
        variantId: Long?,
        price: Double,
        checkedAt: Long,
        label: String? = "100 мл",
        productId: Long = 1L,
    ) = PriceHistoryEntry(
        productId = productId,
        variantId = variantId,
        volumeLabel = label,
        price = price,
        checkedAt = checkedAt,
    )

    @Test
    fun marksChangesOfTheLastCheckOnly() {
        val history = listOf(
            entry(variantId = 1L, price = 5_000.0, checkedAt = 10L),
            entry(variantId = 1L, price = 4_900.0, checkedAt = 20L),
            // У этого объёма цена менялась раньше последней проверки — новости нет, и вечно висеть
            // пометке «подорожало» нельзя.
            entry(variantId = 2L, price = 3_000.0, checkedAt = 5L, label = "50 мл"),
            entry(variantId = 2L, price = 2_900.0, checkedAt = 10L, label = "50 мл"),
        )

        val changes = VariantPriceChanges.atLastCheck(history, lastSuccessAt = 20L)

        assertEquals(1, changes.size)
        assertEquals(1L, changes.first().variantId)
        assertEquals("100 мл", changes.first().label)
        assertEquals(-100.0, changes.first().delta, 0.0)
        assertTrue(changes.first().isFalling)
    }

    @Test
    fun firstKnownPriceIsNotAChange() {
        val history = listOf(entry(variantId = 1L, price = 4_900.0, checkedAt = 20L))

        // Сравнивать не с чем: показать «подорожало» на впервые увиденной цене — значит соврать.
        assertTrue(VariantPriceChanges.atLastCheck(history, lastSuccessAt = 20L).isEmpty())
    }

    @Test
    fun nothingIsMarkedWhenLastCheckIsUnknownOrQuiet() {
        val history = listOf(
            entry(variantId = 1L, price = 5_000.0, checkedAt = 10L),
            entry(variantId = 1L, price = 4_900.0, checkedAt = 20L),
        )

        // Проверяли вообще без отметки времени — сравнивать не с чем.
        assertTrue(VariantPriceChanges.atLastCheck(history, lastSuccessAt = null).isEmpty())
        // Последняя проверка прошла, но цен не коснулась: пометка должна исчезнуть, а не висеть.
        assertTrue(VariantPriceChanges.atLastCheck(history, lastSuccessAt = 30L).isEmpty())
    }

    @Test
    fun historyWithoutVariantsIsIgnored() {
        val history = listOf(
            entry(variantId = null, price = 5_000.0, checkedAt = 10L),
            entry(variantId = null, price = 4_900.0, checkedAt = 20L),
        )

        assertTrue(VariantPriceChanges.atLastCheck(history, lastSuccessAt = 20L).isEmpty())
        assertNull(VariantPriceChanges.bestDrop(history))
    }

    @Test
    fun biggestChangeComesFirst() {
        val history = listOf(
            entry(variantId = 1L, price = 1_000.0, checkedAt = 10L, label = "30 мл"),
            entry(variantId = 1L, price = 990.0, checkedAt = 20L, label = "30 мл"),
            entry(variantId = 2L, price = 500.0, checkedAt = 10L, label = "2 мл"),
            entry(variantId = 2L, price = 700.0, checkedAt = 20L, label = "2 мл"),
        )

        val changes = VariantPriceChanges.atLastCheck(history, lastSuccessAt = 20L)

        assertEquals(listOf(2L, 1L), changes.map { it.variantId })
        assertEquals(200.0, changes.first().delta, 0.0)
    }

    @Test
    fun emptyLabelWhenHistoryIsOlderThanVariants() {
        val history = listOf(
            entry(variantId = 1L, price = 1_000.0, checkedAt = 10L, label = null),
            entry(variantId = 1L, price = 990.0, checkedAt = 20L, label = "   "),
        )

        assertEquals("", VariantPriceChanges.atLastCheck(history, lastSuccessAt = 20L).single().label)
    }

    @Test
    fun bestDropComparesEachVariantWithItsOwnMaximum() {
        val history = listOf(
            // 10 000 -> 9 900: в рублях падение больше, но это 1 %.
            entry(variantId = 1L, price = 10_000.0, checkedAt = 10L, label = "100 мл"),
            entry(variantId = 1L, price = 9_900.0, checkedAt = 30L, label = "100 мл"),
            // 190 -> 90: вдвое дешевле — это и есть «упал сильнее всего».
            entry(variantId = 2L, price = 190.0, checkedAt = 10L, label = "2 мл"),
            entry(variantId = 2L, price = 90.0, checkedAt = 20L, label = "2 мл"),
        )

        val drop = requireNotNull(VariantPriceChanges.bestDrop(history))

        assertEquals(2L, drop.variantId)
        assertEquals("2 мл", drop.label)
        assertEquals(-52.63, drop.percent, 0.01)
        assertEquals(-100.0, drop.delta, 0.0)
        assertEquals(190.0, drop.peakPrice, 0.0)
    }

    @Test
    fun bestDropLooksAtThePeakNotAtTheFirstPrice() {
        val history = listOf(
            entry(variantId = 1L, price = 100.0, checkedAt = 10L),
            entry(variantId = 1L, price = 200.0, checkedAt = 20L, label = "2 мл"),
            entry(variantId = 1L, price = 150.0, checkedAt = 30L, label = "2 мл"),
        )

        // От первой наблюдаемой цены товар даже подорожал (100 -> 150), но упал с пика на 25 % —
        // и для покупателя это именно падение: «сейчас дешевле, чем было месяц назад».
        val drop = requireNotNull(VariantPriceChanges.bestDrop(history))
        assertEquals(200.0, drop.peakPrice, 0.0)
        assertEquals(-25.0, drop.percent, 0.01)
        assertEquals(-50.0, drop.delta, 0.0)
    }

    @Test
    fun bestDropNeedsAPeakBeforeTheCurrentPrice() {
        val onePoint = listOf(entry(variantId = 1L, price = 5_000.0, checkedAt = 10L))
        assertNull(VariantPriceChanges.bestDrop(onePoint))

        val sameTime = listOf(
            entry(variantId = 1L, price = 5_000.0, checkedAt = 10L),
            entry(variantId = 1L, price = 4_900.0, checkedAt = 10L),
        )
        assertNull(VariantPriceChanges.bestDrop(sameTime))

        val grew = listOf(
            entry(variantId = 1L, price = 5_000.0, checkedAt = 10L),
            entry(variantId = 1L, price = 5_100.0, checkedAt = 20L),
        )
        assertNull(VariantPriceChanges.bestDrop(grew))
    }
}
