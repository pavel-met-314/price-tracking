package com.example.otsled.ui.products

import com.example.otsled.domain.PricePoint
import com.example.otsled.domain.model.TrackedProduct
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Флаги, которыми экран решает, что показывать: пуст ли список и не пуст ли он из-за фильтра.
 * Ошибка здесь стоит пользователю «пропавших» карточек: если `isFilterEmpty` посмотреть на
 * пустой список при `totalCount == 0`, экран покажет «под фильтр ничего не попало» там, где
 * товаров просто нет, и наоборот — лишние чипы над пустотой.
 */
class ProductListUiStateTest {
    private fun row(id: Long) = ProductListRow(
        product = TrackedProduct(
            id = id,
            url = "https://allureparfum.ru/katalog/x/$id.html",
            title = "Товар $id",
            targetPrice = null,
            lastPrice = 100.0,
            lastCheckedAt = 1_000L + id,
        ),
        points = listOf(PricePoint(checkedAt = 1_000L, price = 100.0), PricePoint(checkedAt = 2_000L, price = 90.0)),
    )

    @Test
    fun emptyListIsNotFilterEmpty() {
        val state = ProductListUiState(rows = emptyList(), totalCount = 0, filter = ProductFilter.DROPPED)

        assertTrue(state.isEmptyList)
        assertFalse(state.isFilterEmpty)
    }

    @Test
    fun filteredOutListIsMarkedAsFilterEmpty() {
        val state = ProductListUiState(rows = emptyList(), totalCount = 3, filter = ProductFilter.DROPPED)

        assertFalse(state.isEmptyList)
        assertTrue(state.isFilterEmpty)
    }

    @Test
    fun rowsPresentLeaveBothStatesOff() {
        val state = ProductListUiState(rows = listOf(row(1), row(2)), totalCount = 2, filter = ProductFilter.DROPPED)

        assertFalse(state.isEmptyList)
        assertFalse(state.isFilterEmpty)
        assertFalse(state.showsAll)
        assertTrue(ProductListUiState(rows = listOf(row(1)), totalCount = 1).showsAll)
    }
}
