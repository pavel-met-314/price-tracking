package com.example.otsled.ui.products

import com.example.otsled.domain.model.isPaused
import com.example.otsled.domain.model.status

/** Порядок списка отслеживаемых товаров. */
enum class ProductSort {
    /** Как добавляли: новые сверху. Ничего не считаем, самый привычный вид. */
    ADDED,
    NAME,
    PRICE_ASC,
    PRICE_DESC,
    /** Сильнее всего упавшие — то, ради чего список обычно открывают вечером. */
    DROP,
    /** Давно не проверявшиеся (и те, что не проверялись вовсе) — про больное место трекера. */
    STALE,
}

/** Что показывать из списка. */
enum class ProductFilter {
    ALL,
    /** Цена достигла целевой — кандидат на покупку. */
    BELOW_TARGET,
    /** Цена упала относительно предыдущей проверки. */
    DROPPED,
    /** Проверка не проходит: блокировка или серия ошибок парсинга. */
    PROBLEM,
    /** Цену ещё не достали — «цена не проверялась» и пустой график. */
    NO_PRICE,

    /** Отдельный режим, а не фильтр по признаку: архивные товары не должны мешаться в основном. */
    ARCHIVE,
}

/**
 * Сортировка и фильтры списка.
 *
 * Считаются в памяти, а не запросом Room: товаров десятки, а сортировать нужно по полям, которых
 * в таблице нет (падение цены считается по истории). Порядок равных элементов не меняется: товары
 * с одинаковой ценой не должны прыгать при каждом обновлении списка.
 */
object ProductListOrdering {

    /**
     * Архив — противоположный основного режим: в обычном списке архивных не видно вовсе, в архиве
     * — только они. Правилу два, поэтому он вынесен до остальных фильтров: «ниже цели» внутри
     * архива бессмысленно, а «архивный» товар в основном списке быть не должен ни при каком
     * фильтре.
     */
    fun inCurrentView(row: ProductListRow, filter: ProductFilter): Boolean =
        if (filter == ProductFilter.ARCHIVE) row.product.isArchived else !row.product.isArchived

    /** Сколько товаров в текущем режиме — по этому числу заголовок «Показано K из N». */
    fun countInCurrentView(rows: List<ProductListRow>, filter: ProductFilter): Int =
        rows.count { inCurrentView(it, filter) }

    fun apply(rows: List<ProductListRow>, sort: ProductSort, filter: ProductFilter): List<ProductListRow> {
        val kept = rows.filter { inCurrentView(it, filter) }.filter { matches(it, filter) }
        return when (sort) {
            // «Как добавляли» — порядок из базы: id там уже отсортированы DESC.
            ProductSort.ADDED -> kept
            ProductSort.NAME -> kept.sortedBy { it.sortableName() }
            ProductSort.PRICE_ASC ->
                kept.sortedWith(Comparator<ProductListRow> { a, b -> compareNullable(a.price, b.price, nullsFirst = false) })

            ProductSort.PRICE_DESC -> kept.sortedWith(
                Comparator<ProductListRow> { a, b -> comparePriceDescending(a.price, b.price) },
            )

            ProductSort.DROP ->
                kept.sortedWith(Comparator<ProductListRow> { a, b -> compareNullable(a.trendDelta, b.trendDelta, nullsFirst = false) })

            ProductSort.STALE ->
                kept.sortedWith(Comparator<ProductListRow> { a, b -> compareNullable(a.checkedAt, b.checkedAt, nullsFirst = true) })
        }
    }

    fun matches(row: ProductListRow, filter: ProductFilter): Boolean = when (filter) {
        ProductFilter.ALL -> true
        ProductFilter.BELOW_TARGET -> row.isBelowTarget
        ProductFilter.DROPPED -> (row.trendDelta ?: 0.0) < 0.0
        // «С проблемами» — всё, что требует решения: упорные ошибки, блокировка, пауза и то, что
        // товар реально исчез с сайта. Архив сюда не входит: там проблем нет, там выбор пользователя.
        ProductFilter.PROBLEM -> row.product.hasCheckProblem || row.product.isBotBlocked ||
            row.product.status.isProblem || row.product.isPaused
        ProductFilter.NO_PRICE -> row.product.lastPrice == null
        // В архиве дополнительных условий нет: сам режим уже отделил архивные строки выше.
        ProductFilter.ARCHIVE -> true
    }

    private val ProductListRow.price: Double?
        get() = product.lastPrice

    private val ProductListRow.checkedAt: Long?
        get() = product.lastCheckedAt

    /** Цена не выше целевой — то, ради чего цель и задают. */
    private val ProductListRow.isBelowTarget: Boolean
        get() {
            val price = product.lastPrice ?: return false
            val target = product.targetPrice ?: return false
            return price <= target
        }

    /**
     * Убывание цены: отсутствие значения по-прежнему в конце. Отрицать результат
     * compareNullable нельзя — тогда «цены нет» становилось бы «самой большой ценой» и выезжало
     на начало списка (на этом тест и поймал первую версию метода).
     */
    private fun comparePriceDescending(a: Double?, b: Double?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> b.compareTo(a)
    }

    /** Название сравниваем без регистра; если его нет — ссылкой, чтобы товар не уезжал в конец. */
    private fun ProductListRow.sortableName(): String =
        product.title.ifBlank { product.url }.trim().lowercase()

    /**
     * Сравнение значений, которых может не быть. Отсутствие цены — не «минус бесконечность»,
     * а отдельная группа: такие товары уходят в конец (или в начало — для «давно не проверялись»),
     * но не перемешивают остальные.
     */
    private fun <T : Comparable<T>> compareNullable(a: T?, b: T?, nullsFirst: Boolean): Int = when {
        a == null && b == null -> 0
        a == null -> if (nullsFirst) -1 else 1
        b == null -> if (nullsFirst) 1 else -1
        else -> a.compareTo(b)
    }
}
