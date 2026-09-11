package com.example.otsled.domain

import com.example.otsled.domain.model.PriceHistoryEntry

/**
 * Что показывает блок «История цен» в карточке товара.
 *
 * Список — по всем отслеживаемым объёмам, а не по одному выбранному: это журнал того, что мы
 * видели, и прятать за фильтром графика строки 30 и 100 мл значит показывать человеку одну цену
 * в списке и семь других — выше, в ценах по объёмам. График при этом остаётся про один объём,
 * потому что ряд из 2 мл и 100 мл на одной оси врёт (см. `PriceSeries`).
 */
object PriceHistoryListing {

    /** Больше двухсот строк на товар никто не листает; сколько скрыто — говорим отдельно. */
    const val DEFAULT_LIMIT = 200

    data class Listing(val rows: List<PriceHistoryEntry>, val hiddenCount: Int) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    fun rows(history: List<PriceHistoryEntry>, limit: Int = DEFAULT_LIMIT): Listing {
        val ordered = history.sortedWith(
            // Стабильная сортировка: одна минута у двух объёмов — это один прогон, и порядок
            // внутри него должен соответствовать ценам сверху (объём поменьше — раньше).
            compareByDescending<PriceHistoryEntry> { it.checkedAt }
                .thenBy { it.volumeLabel.orEmpty() },
        )
        val size = limit.coerceIn(1, ordered.size.coerceAtLeast(1))

        return Listing(
            rows = ordered.take(size),
            hiddenCount = (ordered.size - size).coerceAtLeast(0),
        )
    }
}
