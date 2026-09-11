package com.example.otsled.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порог уведомлений и тихие часы. Правило, ради которого написан этот файл: настройка «не мешать»
 * не имеет права терять данные — она убирает звук, а не запись в истории, и не даёт пропустить то,
 * ради чего приложение и стоит («цель достигнута» молчать не может).
 */
class NotificationPolicyTest {

    private fun policy(percent: Int = 0, quiet: Boolean = false, start: Int = 22 * 60, end: Int = 7 * 60) =
        AlertPolicy(minChangePercent = percent, quietHoursEnabled = quiet, quietStartMinute = start, quietEndMinute = end)

    @Test
    fun thresholdIsAbsoluteAndInclusive() {
        assertTrue(NotificationPolicy.isWorthAnnouncing(-1.0, minChangePercent = 1))
        assertTrue(NotificationPolicy.isWorthAnnouncing(1.0, minChangePercent = 1))
        assertFalse(NotificationPolicy.isWorthAnnouncing(-0.9, minChangePercent = 1))
        assertTrue("порог 0 — сообщать о любом изменении", NotificationPolicy.isWorthAnnouncing(0.01, 0))
    }

    @Test
    fun unknownPercentIsAlwaysAnnounced() {
        // Сравнивать было не с чем (первое наблюдение объёма) — молчать нельзя: «неизвестно» и
        // «не важно» для человека разные новости.
        assertTrue(NotificationPolicy.isWorthAnnouncing(null, minChangePercent = 50))
    }

    @Test
    fun quietWindowThroughMidnight() {
        val quiet = NotificationPolicy.isQuietMinute(23 * 60, 22 * 60, 7 * 60)
        val alsoQuiet = NotificationPolicy.isQuietMinute(3 * 60, 22 * 60, 7 * 60)
        val before = NotificationPolicy.isQuietMinute(21 * 60 + 59, 22 * 60, 7 * 60)
        val after = NotificationPolicy.isQuietMinute(7 * 60, 22 * 60, 7 * 60)

        assertTrue(quiet)
        assertTrue(alsoQuiet)
        assertFalse("21:59 ещё не ночь", before)
        assertFalse("07:00 — уже утро, конец окна не включается", after)
    }

    @Test
    fun quietWindowInsideOneDay() {
        assertTrue(NotificationPolicy.isQuietMinute(13 * 60, 12 * 60, 14 * 60))
        assertFalse(NotificationPolicy.isQuietMinute(15 * 60, 12 * 60, 14 * 60))
    }

    @Test
    fun emptyWindowMeansOff() {
        // «03:00–03:00» не должно глушить сутки: иначе одна неверная настройка молча
        // обнуляет все уведомления.
        assertFalse(NotificationPolicy.isQuietMinute(3 * 60, 3 * 60, 3 * 60))
    }

    @Test
    fun minutesOfDayWrapsAndClamps() {
        assertEquals(0, NotificationPolicy.minutesOfDay(0, 0))
        assertEquals(23 * 60 + 59, NotificationPolicy.minutesOfDay(23, 59))
        assertEquals(0, NotificationPolicy.minutesOfDay(24, 0))
    }

    @Test
    fun priceChangeRespectsThresholdFirstThenQuietHours() {
        val quiet = policy(percent = 3, quiet = true)

        assertEquals(AlertDelivery.NONE, NotificationPolicy.deliveryForPriceChange(1.0, 23 * 60, quiet))
        assertEquals(AlertDelivery.SILENT, NotificationPolicy.deliveryForPriceChange(-5.0, 23 * 60, quiet))
        assertEquals(AlertDelivery.IMMEDIATE, NotificationPolicy.deliveryForPriceChange(-5.0, 12 * 60, quiet))
    }

    @Test
    fun importantNewsIsNeverSwallowed() {
        val quiet = policy(percent = 50, quiet = true)

        // Порог в процентах к «цель достигнута» не применяется, а тихие часы лишь убирают звук.
        assertEquals(AlertDelivery.SILENT, NotificationPolicy.deliveryForImportantNews(2 * 60, quiet))
        assertEquals(AlertDelivery.IMMEDIATE, NotificationPolicy.deliveryForImportantNews(12 * 60, quiet))
    }

    @Test
    fun disabledQuietHoursDeliverImmediately() {
        assertEquals(
            AlertDelivery.IMMEDIATE,
            NotificationPolicy.deliveryForPriceChange(-10.0, 3 * 60, policy(quiet = false)),
        )
    }
}
