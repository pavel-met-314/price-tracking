package com.example.otsled.domain

import com.example.otsled.domain.model.PriceHistoryEntry
import kotlin.math.abs

/**
 * Изменение цены одного объёма: его последняя запись в истории против предыдущей.
 *
 * Корректно сравнивать только «тот же объём с тем же объёмом». История пишется на каждый
 * изменившийся вариант, поэтому ряд «цена от по всем объёмам» прыгает между пробником 2 мл и
 * флаконом 100 мл и выдаёт это за рост или падение цены — на этом и ломался мини-график в
 * списке: цена падала, а линия шла вверх.
 */
data class VariantPriceChange(
    val variantId: Long,
    /** Подпись объёма из истории, например «100 мл». Пустая, если история старше таблицы вариантов. */
    val label: String,
    /** Новое минус старое в рублях. Отрицательное — цена упала. */
    val delta: Double,
) {
    val isFalling: Boolean get() = delta < 0.0
}

/** Насколько текущая цена объёма ниже его же цены на пике. */
data class VariantDrop(
    val variantId: Long,
    val label: String,
    /** Проценты, отрицательные: -8.5 значит «упало на 8,5 %». */
    val percent: Double,
    /** То же в рублях — для подписи. */
    val delta: Double,
    /** Пик, от которого считали падение. */
    val peakPrice: Double,
)

object VariantPriceChanges {

    /**
     * Что изменилось на последней успешной проверке. Запись в истории появляется только когда цена
     * изменилась, поэтому `checkedAt == lastSuccessAt` — это ровно изменения текущего прогона, а
     * предыдущая запись того же варианта — то, с чем сравнивать. Неудачные прогоны (сайт не отдал
     * страницу) время успеха не сдвигают, и вчерашняя новость доживёт до следующей удачной проверки.
     *
     * Ограничение «только последняя проверка» принципиальное: иначе пометка о подорожании
     * месячной давности висела бы в списке навсегда.
     */
    fun atLastCheck(history: List<PriceHistoryEntry>, lastSuccessAt: Long?): List<VariantPriceChange> {
        if (lastSuccessAt == null) return emptyList()
        return byVariant(history)
            .mapNotNull { (variantId, entries) ->
                val sorted = entries.sortedBy { it.checkedAt }
                val newest = sorted.lastOrNull() ?: return@mapNotNull null
                if (newest.checkedAt != lastSuccessAt) return@mapNotNull null
                val previous = sorted.dropLast(1).lastOrNull() ?: return@mapNotNull null
                val delta = newest.price - previous.price
                if (delta == 0.0) return@mapNotNull null
                VariantPriceChange(variantId, label(newest), delta)
            }
            .sortedByDescending { abs(it.delta) }
    }

    /**
     * Самое сильное падение среди объёмов товара: текущая цена объёма против его же пика.
     * Пик, а не «цена, с которой начали следить», — иначе одна неудачная покупка по завышенной
     * цене превращается в «товар не дешевел».
     */
    fun bestDrop(history: List<PriceHistoryEntry>): VariantDrop? = byVariant(history)
        .mapNotNull { (variantId, entries) ->
            val newest = entries.maxByOrNull { it.checkedAt } ?: return@mapNotNull null
            val peak = entries.filter { it.checkedAt < newest.checkedAt }
                .maxByOrNull { it.price }
                ?: return@mapNotNull null
            if (peak.price <= 0.0) return@mapNotNull null
            val delta = newest.price - peak.price
            if (delta >= 0.0) return@mapNotNull null
            VariantDrop(
                variantId = variantId,
                label = label(newest),
                percent = delta / peak.price * 100.0,
                delta = delta,
                peakPrice = peak.price,
            )
        }
        .minByOrNull { it.percent }

    /** Записи без варианта — из истории до таблицы объёмов: сравнивать их не с чем, выбрасываем. */
    private fun byVariant(history: List<PriceHistoryEntry>): Map<Long, List<PriceHistoryEntry>> = history
        .mapNotNull { entry -> entry.variantId?.let { it to entry } }
        .groupBy({ it.first }, { it.second })

    private fun label(entry: PriceHistoryEntry): String = entry.volumeLabel?.trim().orEmpty()
}
