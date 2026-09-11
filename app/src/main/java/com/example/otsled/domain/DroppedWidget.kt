package com.example.otsled.domain

/**
 * Содержимое виджета «что подешевело». Все вычисления — здесь, а не в RemoteViews: у виджета
 * нет ни отладчика, ни тестов на телефоне, и единственная защита от вранья на домашнем экране —
 * чистая функция, покрытая юнит-тестами.
 */
data class WidgetPriceRow(
    val productId: Long,
    val title: String,
    /** Текущая «цена от». */
    val price: Double,
    /** Пик, от которого считали падение, — чтобы виджет не врал «подешевело на 8 %» без базы. */
    val peakPrice: Double,
    val percent: Double,
    val volumeLabel: String,
)

data class DroppedWidgetModel(
    val rows: List<WidgetPriceRow>,
    /** Сколько ещё товаров подешевели, но не влезли в виджет. */
    val hiddenCount: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

object DroppedWidget {

    const val MAX_ROWS = 4

    /** Больше сорока символов в виджете не читается: строка обрезается, а не переносится. */
    const val MAX_TITLE_LENGTH = 34

    data class Candidate(
        val productId: Long,
        val title: String,
        val price: Double?,
        val drop: VariantDrop?,
    )

    fun build(candidates: List<Candidate>, maxRows: Int = MAX_ROWS): DroppedWidgetModel {
        val falling = candidates
            .mapNotNull { candidate ->
                val price = candidate.price
                val drop = candidate.drop
                // Без цены показывать нечего; без падения — это не «подешевело», а «ничего не
                // происходило», и в виджете такому месту не место.
                if (price == null || drop == null || drop.percent >= 0.0) return@mapNotNull null
                WidgetPriceRow(
                    productId = candidate.productId,
                    title = shorten(candidate.title),
                    price = price,
                    peakPrice = drop.peakPrice,
                    percent = drop.percent,
                    volumeLabel = drop.label,
                )
            }
            .sortedBy { it.percent }

        return DroppedWidgetModel(
            rows = falling.take(maxRows.coerceAtLeast(0)),
            hiddenCount = (falling.size - maxRows).coerceAtLeast(0),
        )
    }

    fun shorten(title: String, limit: Int = MAX_TITLE_LENGTH): String {
        val trimmed = title.trim()
        if (trimmed.length <= limit) return trimmed
        return trimmed.take(limit - 1).trimEnd() + "…"
    }
}
