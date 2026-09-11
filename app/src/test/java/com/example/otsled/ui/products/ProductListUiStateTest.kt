package com.example.otsled.ui.products

import com.example.otsled.domain.model.TrackedProduct
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Флаги, которыми экран решает, что показывать: пуст ли список, не пуст ли он из-за режима, и
 * какие чипы рисовать. Ошибка здесь стоит пользователю «пропавших» товаров: на пустом архиве
 * экран считал список пустым, прятал чипы и предлагал «добавьте ссылку» — выбраться из режима
 * было нечем, хотя пять отслеживаемых товаров оставались на месте.
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
    )

    @Test
    fun emptyTableIsTheOnlyRealEmptyState() {
        val state = ProductListUiState(rows = emptyList(), totalCount = 0, allCount = 0, filter = ProductFilter.DROPPED)

        assertTrue(state.isEmptyList)
        assertFalse(state.isFilterEmpty)
    }

    @Test
    fun emptyArchiveIsNotAnEmptyTable() {
        // Пять товаров в базе, пользователь в режиме «Архив», и архив пуст: `totalCount == 0`,
        // но это пустой режим, а не пустой список.
        val state = ProductListUiState(rows = emptyList(), totalCount = 0, allCount = 5, filter = ProductFilter.ARCHIVE)

        assertFalse(state.isEmptyList)
        assertTrue(state.isFilterEmpty)
        assertTrue(state.showsArchive)
        assertFalse(state.showsAll)
    }

    @Test
    fun filteredOutListIsMarkedAsFilterEmpty() {
        val state = ProductListUiState(rows = emptyList(), totalCount = 3, allCount = 3, filter = ProductFilter.DROPPED)

        assertFalse(state.isEmptyList)
        assertTrue(state.isFilterEmpty)
        assertFalse(state.showsArchive)
    }

    @Test
    fun rowsPresentLeaveBothStatesOff() {
        val state = ProductListUiState(
            rows = listOf(row(1), row(2)),
            totalCount = 2,
            allCount = 2,
            filter = ProductFilter.DROPPED,
        )

        assertFalse(state.isEmptyList)
        assertFalse(state.isFilterEmpty)
        assertFalse(state.showsAll)
        assertTrue(ProductListUiState(rows = listOf(row(1)), totalCount = 1, allCount = 1).showsAll)
    }
}
