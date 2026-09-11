package com.example.otsled.ui.products

import com.example.otsled.domain.VariantDrop
import com.example.otsled.domain.VariantPriceChange
import com.example.otsled.domain.model.TrackedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок и фильтры списка. Это чистая функция без Room и без Compose — и именно в ней легче всего
 * сделать вид, что всё в порядке: товар без цены уедет в начало «по возрастанию цены», а «упавшие»
 * окажутся теми, у кого история короче двух точек.
 */
class ProductListOrderingTest {

    /** Падение одного объёма с `from` до `to` — так же его считает [com.example.otsled.domain.VariantPriceChanges]. */
    private fun drop(from: Double, to: Double) = VariantDrop(
        variantId = 1L,
        label = "100 мл",
        percent = (to - from) / from * 100.0,
        delta = to - from,
        peakPrice = from,
    )

    private fun row(
        id: Long,
        title: String,
        price: Double? = null,
        target: Double? = null,
        checkedAt: Long? = null,
        changes: List<VariantPriceChange> = emptyList(),
        drop: VariantDrop? = null,
        failures: Int = 0,
        errorCode: String = "",
        archivedAt: Long? = null,
        isActive: Boolean = true,
    ) = ProductListRow(
        product = TrackedProduct(
            id = id,
            url = "https://allureparfum.ru/katalog/x/$id.html",
            title = title,
            targetPrice = target,
            lastPrice = price,
            lastCheckedAt = checkedAt,
            consecutiveFailures = failures,
            lastErrorCode = errorCode,
            isActive = isActive,
            archivedAt = archivedAt,
        ),
        changes = changes,
        drop = drop,
    )

    @Test
    fun addedOrderKeepsWhatTheDatabaseGave() {
        val rows = listOf(row(3, "Ганнимед"), row(1, "Аве"))

        val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.ALL)

        assertEquals(listOf(3L, 1L), kept.map { it.product.id })
    }

    @Test
    fun nameSortIgnoresCaseAndFallsBackToUrlWhenTitleIsEmpty() {
        val rows = listOf(row(1, "бета"), row(2, "Альфа"), row(3, "   "))

        val sorted = ProductListOrdering.apply(rows, ProductSort.NAME, ProductFilter.ALL)

        // Безымянный товар сравнивается по ссылке; латиница в URL идёт раньше кириллицы — порядок
        // детерминированный, и важно другое: такой товар не прилипает к концу списка и не теряется.
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.product.id })
    }

    @Test
    fun priceSortKeepsProductsWithoutPriceAtTheEnd() {
        val rows = listOf(row(1, "а", price = 500.0), row(2, "б"), row(3, "в", price = 100.0))

        val asc = ProductListOrdering.apply(rows, ProductSort.PRICE_ASC, ProductFilter.ALL)
        val desc = ProductListOrdering.apply(rows, ProductSort.PRICE_DESC, ProductFilter.ALL)

        assertEquals(listOf(3L, 1L, 2L), asc.map { it.product.id })
        assertEquals(listOf(1L, 3L, 2L), desc.map { it.product.id })
    }

    @Test
    fun dropSortComparesPercentagesNotRubles() {
        val rows = listOf(
            row(1, "не менялся", price = 100.0),
            row(2, "упал сильно", price = 90.0, drop = drop(from = 190.0, to = 90.0)),
            row(3, "упал чуть", price = 180.0, drop = drop(from = 190.0, to = 180.0)),
            row(4, "без истории", price = 10.0),
            // В рублях это падение самое большое (-100), и старая версия порядка ставила его первой.
            // На самом деле цена ушла на 1 % — товар дешевел с 10 000, а не со 190.
            row(5, "упал на 100 из 10 000", price = 9_900.0, drop = drop(from = 10_000.0, to = 9_900.0)),
        )

        val sorted = ProductListOrdering.apply(rows, ProductSort.DROP, ProductFilter.ALL)

        assertEquals(listOf(2L, 3L, 5L, 1L, 4L), sorted.map { it.product.id })
    }

    @Test
    fun staleSortStartsWithNeverChecked() {
        val rows = listOf(
            row(1, "свежий", checkedAt = 5_000L),
            row(2, "забытый", checkedAt = 1_000L),
            row(3, "ни разу"),
        )

        val sorted = ProductListOrdering.apply(rows, ProductSort.STALE, ProductFilter.ALL)

        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.product.id })
    }

    @Test
    fun belowTargetFilterNeedsBothPrices() {
        val rows = listOf(
            row(1, "ниже цели", price = 900.0, target = 1_000.0),
            row(2, "выше цели", price = 1_200.0, target = 1_000.0),
            row(3, "цели нет", price = 10.0),
            row(4, "цены нет", target = 1_000.0),
        )

        val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.BELOW_TARGET)

        assertEquals(listOf(1L), kept.map { it.product.id })
    }

    @Test
    fun droppedFilterNeedsActualFall() {
        val rows = listOf(
            row(1, "упал", price = 90.0, drop = drop(from = 100.0, to = 90.0)),
            row(2, "вырос", price = 110.0, changes = listOf(VariantPriceChange(variantId = 1L, label = "100 мл", delta = 10.0))),
            row(3, "цену не наблюдали", price = 100.0),
        )

        val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.DROPPED)

        assertEquals(listOf(1L), kept.map { it.product.id })
    }

    @Test
    fun problemFilterCatchesFailuresAndBotBlock() {
        val rows = listOf(
            row(1, "сбой", failures = 2),
            row(2, "блок", errorCode = "BOT_CHALLENGE"),
            row(3, "всё хорошо"),
        )

        val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.PROBLEM)

        assertEquals(listOf(1L, 2L), kept.map { it.product.id })
    }

    @Test
    fun noPriceFilterShowsOnlyProductsWithoutPrice() {
        val rows = listOf(row(1, "с ценой", price = 100.0), row(2, "без цены"))

        val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.NO_PRICE)

        assertEquals(listOf(2L), kept.map { it.product.id })
    }

    @Test
    fun filterAndSortAreAppliedTogether() {
        val rows = listOf(
            row(1, "упал и ниже цели", price = 90.0, target = 100.0, drop = drop(from = 120.0, to = 90.0)),
            row(2, "вырос", price = 130.0, target = 100.0),
            row(3, "упал, цели нет", price = 80.0, drop = drop(from = 120.0, to = 80.0)),
        )

        val kept = ProductListOrdering.apply(rows, ProductSort.PRICE_ASC, ProductFilter.BELOW_TARGET)

        assertEquals(listOf(1L), kept.map { it.product.id })
    }

    @Test
    fun equalValuesKeepRelativeOrderAndEmptyInputStaysEmpty() {
        val rows = listOf(row(1, "б", price = 100.0), row(2, "а", price = 100.0))

        val sorted = ProductListOrdering.apply(rows, ProductSort.PRICE_ASC, ProductFilter.ALL)

        assertEquals(listOf(1L, 2L), sorted.map { it.product.id })
        assertTrue(ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.ALL).isNotEmpty())
        assertFalse(ProductListOrdering.apply(emptyList(), ProductSort.NAME, ProductFilter.PROBLEM).isNotEmpty())
    }

    @Test
    fun archivedProductsAreHiddenFromEveryOrdinaryView() {
        val rows = listOf(
            row(1, "Аве", price = 100.0),
            row(2, "Ганнимед", price = 200.0, archivedAt = 10L),
            row(3, "Сидна", price = null),
        )

        for (filter in ProductFilter.entries.filter { it != ProductFilter.ARCHIVE }) {
            val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, filter)
            assertFalse("архивный товар просочился в фильтр $filter", kept.any { it.product.id == 2L })
        }
        // «Товаров: 3» при двух показанных значило бы «куда-то делся один» — счётчик считает
        // текущий режим, а не всю таблицу.
        assertEquals(2, ProductListOrdering.countInCurrentView(rows, ProductFilter.ALL))
    }

    @Test
    fun archiveViewShowsOnlyArchived() {
        val rows = listOf(
            row(1, "Аве", price = 100.0),
            row(2, "Ганнимед", price = 200.0, archivedAt = 10L),
        )

        val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.ARCHIVE)

        assertEquals(listOf(2L), kept.map { it.product.id })
        assertEquals(1, ProductListOrdering.countInCurrentView(rows, ProductFilter.ARCHIVE))
    }

    @Test
    fun problemFilterCoversPausesAndGoneProducts() {
        val rows = listOf(
            row(1, "Аве", price = 100.0),
            row(2, "На паузе", price = 100.0, isActive = false),
            row(3, "Нет страницы", failures = 1, errorCode = "NOT_FOUND"),
            row(4, "Всё хорошо", price = 100.0),
        )

        val kept = ProductListOrdering.apply(rows, ProductSort.ADDED, ProductFilter.PROBLEM)

        // «Нет страницы» попадает и с одним неудачным прогоном: это наблюдение за товаром, а не наш
        // сбой, и ждать трёх подряд, чтобы показать его человеку, не за чем.
        assertEquals(listOf(2L, 3L), kept.map { it.product.id })
    }
}
