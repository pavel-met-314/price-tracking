package com.example.otsled.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Форматирование цен для интерфейса. Группы тысяч — неразрывным пробелом, дробная часть — только
 * когда она есть (4 200 ₽, а не 4 200,00 ₽). Без [java.text.NumberFormat], чтобы вывод не зависел
 * от локали устройства: приложение русскоязычное и цифры должны быть одинаковыми на всех телефонах.
 */
object PriceFormatter {

    /** Тысячеразрядная длина, при которой дробная часть уже не нужна. */
    private const val MINOR_UNITS_PER_RUBLE = 100

    fun formatPrice(value: Double): String {
        val cents = (abs(value) * MINOR_UNITS_PER_RUBLE).roundToInt()
        val rubles = cents / MINOR_UNITS_PER_RUBLE
        val fraction = cents % MINOR_UNITS_PER_RUBLE

        return buildString {
            if (value < 0) append('-')
            append(groupThousands(rubles.toLong()))
            if (fraction != 0) {
                append(',')
                append(fraction.toString().padStart(2, '0'))
            }
        }
    }

    /** Со знаком: "+350" / "-120" / "0" — для дельт. */
    fun formatSigned(value: Double): String {
        val formatted = formatPrice(abs(value))
        return when {
            value > 0 -> "+$formatted"
            value < 0 -> "-$formatted"
            else -> formatted
        }
    }

    /**
     * Процент изменения от [from] к [to] с одним знаком после запятой. При нулевой базе
     * процент бессмысленен, поэтому null, а не «бесконечность» или 0.
     */
    fun formatPercent(from: Double, to: Double): String? {
        if (from == 0.0) return null
        val percent = (to - from) / from * 100
        val tenths = (abs(percent) * 10).roundToInt()
        val whole = tenths / 10
        val fraction = tenths % 10
        val sign = if (percent < 0) "-" else "+"
        val digits = groupThousands(whole.toLong())
        return if (fraction == 0) "$sign$digits%" else "$sign$digits,$fraction%"
    }

    private fun groupThousands(value: Long): String =
        value.toString().reversed().chunked(3).joinToString("\u00A0").reversed()
}
