package com.example.otsled.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Цифры в интерфейсе — то, по чему человек принимает решение о покупке, поэтому формат
 * проверяем так же строго, как парсинг: NBSP в тысячах, копейки только когда они есть.
 */
class PriceFormatterTest {

    private val nbsp = "\u00A0"

    @Test
    fun integerPriceHasNoKopecks() {
        assertEquals("990", PriceFormatter.formatPrice(990.0))
        assertEquals("4$nbsp990", PriceFormatter.formatPrice(4990.0))
    }

    @Test
    fun thousandsGroupedByNonBreakingSpace() {
        assertEquals("4$nbsp990", PriceFormatter.formatPrice(4990.0))
        assertEquals("1${nbsp}234${nbsp}567", PriceFormatter.formatPrice(1234567.0))
    }

    @Test
    fun kopecksShownOnlyWhenPresent() {
        assertEquals("4$nbsp990,50", PriceFormatter.formatPrice(4990.5))
        assertEquals("4$nbsp990,05", PriceFormatter.formatPrice(4990.05))
    }

    @Test
    fun negativePriceKeepsSign() {
        assertEquals("-120", PriceFormatter.formatPrice(-120.0))
    }

    @Test
    fun signedValueCarriesDeltaSign() {
        assertEquals("+350", PriceFormatter.formatSigned(350.0))
        assertEquals("-350", PriceFormatter.formatSigned(-350.0))
        assertEquals("0", PriceFormatter.formatSigned(0.0))
    }

    @Test
    fun percentRoundedToOneDecimalAndTrimmed() {
        assertEquals("-10%", PriceFormatter.formatPercent(4990.0, 4490.0))
        assertEquals("-18,3%", PriceFormatter.formatPercent(3000.0, 2450.0))
        assertEquals("+4%", PriceFormatter.formatPercent(4990.0, 5190.0))
    }

    @Test
    fun percentWithoutBaseIsNull() {
        assertNull(PriceFormatter.formatPercent(0.0, 100.0))
    }
}
