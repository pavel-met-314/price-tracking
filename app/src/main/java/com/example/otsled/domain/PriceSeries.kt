package com.example.otsled.domain

import com.example.otsled.domain.model.PriceHistoryEntry

/** Одна точка на графике: когда и какая цена. */
data class PricePoint(
    val checkedAt: Long,
    val price: Double,
)

/**
 * Готовые для графика и подписей значения. Считается отдельно от UI, чтобы логику можно было
 * покрыть тестами: ошибка в «минимуме» или «дельте» выглядит как правдоподобная цифра, и
 * глазами её не поймать.
 */
data class PriceSeries(
    /** Точки по возрастанию времени, не больше [MAX_POINTS]. */
    val points: List<PricePoint>,
    val min: PricePoint?,
    val max: PricePoint?,
    val current: PricePoint?,
    val previous: PricePoint?,
    val average: Double?,
    /** Сколько самых старых точек отброшено при ограничении. */
    val droppedPoints: Int = 0,
) {
    /** Нужен ли график вообще: одна точка — это ещё не динамика. */
    val hasTrend: Boolean get() = points.size >= 2

    val deltaFromPrevious: Double?
        get() {
            val last = current ?: return null
            val before = previous ?: return null
            return last.price - before.price
        }

    val deltaFromFirst: Double?
        get() {
            val first = points.firstOrNull() ?: return null
            val last = current ?: return null
            if (first.checkedAt == last.checkedAt) return null
            return last.price - first.price
        }

    /** Цена упала — то, ради чего такое приложение вообще ставят. */
    val isFalling: Boolean get() = (deltaFromPrevious ?: 0.0) < 0.0

    companion object {
        /** Больше ста точек на маленьком экране читаются плохо, а считать их дороже. */
        const val MAX_POINTS = 120

        val EMPTY = PriceSeries(
            points = emptyList(),
            min = null,
            max = null,
            current = null,
            previous = null,
            average = null,
        )
    }
}

/**
 * История -> ряд для графика. В истории лежит по записи на каждый *изменившийся* объём, поэтому
 * для обзора «цена от» сначала склеиваем записи одного момента проверки в одну точку (минимум
 * по ней), иначе линия скакала бы между разными объёмами.
 */
fun buildPriceSeries(points: List<PricePoint>): PriceSeries {
    if (points.isEmpty()) return PriceSeries.EMPTY

    val byTime = points
        .groupBy { it.checkedAt }
        .map { (time, entries) -> PricePoint(time, entries.minOf { it.price }) }
        .sortedBy { it.checkedAt }

    val dropped = (byTime.size - PriceSeries.MAX_POINTS).coerceAtLeast(0)
    val visible = if (dropped > 0) byTime.takeLast(PriceSeries.MAX_POINTS) else byTime

    var minPoint = visible.first()
    var maxPoint = visible.first()
    var sum = 0.0
    visible.forEach { point ->
        if (point.price < minPoint.price) minPoint = point
        if (point.price > maxPoint.price) maxPoint = point
        sum += point.price
    }

    return PriceSeries(
        points = visible,
        min = minPoint,
        max = maxPoint,
        current = visible.lastOrNull(),
        previous = if (visible.size >= 2) visible[visible.size - 2] else null,
        average = sum / visible.size,
        droppedPoints = dropped,
    )
}

/** Тот же расчёт, но из истории БД; [variantId] = null — обзорная «цена от» по всем объёмам. */
fun buildPriceSeriesFromHistory(
    history: List<PriceHistoryEntry>,
    variantId: Long? = null,
): PriceSeries {
    val filtered = if (variantId == null) history else history.filter { it.variantId == variantId }
    return buildPriceSeries(filtered.map { PricePoint(it.checkedAt, it.price) })
}
