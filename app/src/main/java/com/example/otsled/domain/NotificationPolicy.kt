package com.example.otsled.domain

import kotlin.math.abs

/** Что делать с уведомлением. Тишина — не то же самое, что молчание: см. [SILENT]. */
enum class AlertDelivery {
    /** Не показывать вовсе. */
    NONE,

    /** Положить в теневой канал: без звука и без всплывающего окна, но в шторке лежит. */
    SILENT,

    /** Как обычно. */
    IMMEDIATE,
}

/**
 * Настройки оповещений. Процент и тихие часы — глобальные, а не на товар: порог «не дёргать меня
 * из-за 30 ₽» и «не буди меня ночью» — решения про человека, а не про флакон.
 */
data class AlertPolicy(
    /** Изменение меньше этого процента не удостоверяется уведомлением. 0 — сообщать о любом. */
    val minChangePercent: Int,
    val quietHoursEnabled: Boolean,
    /** Границы окна в минутах от полуночи; окно может идти через полночь (22:00 → 07:00). */
    val quietStartMinute: Int,
    val quietEndMinute: Int,
) {
    companion object {
        val DEFAULT = AlertPolicy(minChangePercent = 0, quietHoursEnabled = false, 22 * 60, 7 * 60)
    }
}

object NotificationPolicy {

    /**
     * Порог применяется только к «цена подвинулась». Цели достигли — об этом сообщаем при любом
     * пороге: это то, ради чего цель и задавали, и «упало на 0,4 %, но ниже цели» молчать не должно.
     */
    fun isWorthAnnouncing(percentChange: Double?, minChangePercent: Int): Boolean {
        if (percentChange == null) return true
        if (minChangePercent <= 0) return true
        return abs(percentChange) >= minChangePercent
    }

    /**
     * Минута суток внутри тихого окна. Пустое окно (границы совпали) — выключено: иначе
     * «тихие часы 03:00–03:00» заглушали бы весь день.
     */
    fun isQuietMinute(minutesOfDay: Int, quietStartMinute: Int, quietEndMinute: Int): Boolean {
        if (quietStartMinute == quietEndMinute) return false
        val minute = minutesOfDay.mod(MINUTES_PER_DAY)
        return if (quietStartMinute < quietEndMinute) {
            minute >= quietStartMinute && minute < quietEndMinute
        } else {
            // Окно через полночь: 22:00–07:00 — это «позже 22:00» ИЛИ «раньше 07:00».
            minute >= quietStartMinute || minute < quietEndMinute
        }
    }

    fun minutesOfDay(hour: Int, minute: Int): Int = (hour * 60 + minute).mod(MINUTES_PER_DAY)

    /** Решение для «цена изменилась». */
    fun deliveryForPriceChange(
        percentChange: Double?,
        minuteOfDay: Int,
        policy: AlertPolicy,
    ): AlertDelivery = when {
        !isWorthAnnouncing(percentChange, policy.minChangePercent) -> AlertDelivery.NONE
        isQuiet(policy, minuteOfDay) -> AlertDelivery.SILENT
        else -> AlertDelivery.IMMEDIATE
    }

    /**
     * Решение для важного: «цель достигнута» и «мы поставили товар на паузу». Порог в процентах к
     * ним не применяется, а тихие часы лишь убирают звук — новость не должна теряться.
     */
    fun deliveryForImportantNews(minuteOfDay: Int, policy: AlertPolicy): AlertDelivery =
        if (isQuiet(policy, minuteOfDay)) AlertDelivery.SILENT else AlertDelivery.IMMEDIATE

    private fun isQuiet(policy: AlertPolicy, minuteOfDay: Int): Boolean =
        policy.quietHoursEnabled && isQuietMinute(minuteOfDay, policy.quietStartMinute, policy.quietEndMinute)

    private const val MINUTES_PER_DAY = 24 * 60
}
