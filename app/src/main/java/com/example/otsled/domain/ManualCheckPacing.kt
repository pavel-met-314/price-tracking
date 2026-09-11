package com.example.otsled.domain

/**
 * Ритм ручной проверки «обновить всё». Отдельно от службы и БД, потому что здесь арифметика,
 * а её легко испортить: человек нажал кнопку один раз и не должен гадать, почему список обновился
 * наполовину.
 */
data class PacingPlan(
    /** Сколько товаров влезет в остаток цикла. */
    val fits: Int,
    /** Сколько не успеем — их надо назвать, а не прятать: «остальные догрузит плановая проверка». */
    val deferred: Int,
    /** На сколько затянется весь прогон — для подписи кнопки до нажатия. */
    val estimatedMillis: Long,
) {
    val everythingFits: Boolean get() = deferred == 0
}

object ManualCheckPacing {

    /**
     * Пауза между товарами. Без неё десяток ссылок улетает в магазин одним залпом, а анти-бот на
     * allureparfum.ru отвечает на это проверкой браузера — то есть сорванной проверкой.
     */
    const val DELAY_BETWEEN_CHECKS_MILLIS = 1_200L

    /**
     * Бюджет ручного прогона. Меньше, чем у фонового цикла: нажатую вручную проверку человек
     * дожидается, и врать про «пять минут» хуже, чем недосмотреть пару товаров.
     */
    const val MANUAL_BUDGET_MILLIS = 4 * 60_000L

    /** На сколько тянет товар, пока средний срок ещё не накопился. */
    const val FIRST_CHECK_ESTIMATE_MILLIS = 3_000L

    /**
     * План на текущий момент. [avgCheckMillis] — среднее, что уже заняла проверка одного товара в
     * этом прогоне; до первой проверки берём [FIRST_CHECK_ESTIMATE_MILLIS].
     */
    fun plan(
        total: Int,
        alreadyDone: Int,
        elapsedMillis: Long,
        avgCheckMillis: Long?,
        delayMillis: Long = DELAY_BETWEEN_CHECKS_MILLIS,
        budgetMillis: Long = MANUAL_BUDGET_MILLIS,
    ): PacingPlan {
        val remaining = total - alreadyDone
        if (remaining <= 0) return PacingPlan(fits = 0, deferred = 0, estimatedMillis = 0L)

        val perItem = (avgCheckMillis ?: FIRST_CHECK_ESTIMATE_MILLIS).coerceAtLeast(0L) + delayMillis
        val remainingBudget = (budgetMillis - elapsedMillis).coerceAtLeast(0L)
        val fits = if (perItem <= 0L) remaining else (remainingBudget / perItem).toInt().coerceAtMost(remaining)

        return PacingPlan(
            fits = fits,
            deferred = remaining - fits,
            estimatedMillis = remaining * perItem,
        )
    }
}
