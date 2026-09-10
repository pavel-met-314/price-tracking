package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.PricePoint
import com.example.otsled.domain.buildPriceSeriesFromHistory
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.TrackedProduct
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Товар вместе с рядом точек для мини-графика. */
data class ProductListRow(
    val product: TrackedProduct,
    val points: List<PricePoint>,
) {
    /** Изменение цены за последний шаг — по нему карточка краснеет или зеленеет. */
    val trendDelta: Double?
        get() {
            if (points.size < 2) return null
            return points.last().price - points.first().price
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProductListViewModel(
    container: AppContainer,
) : ViewModel() {
    private val repository = container.productRepository

    /**
     * id товаров — вход для одного запроса истории по всем сразу. `if (isEmpty)` обязателен:
     * Room выдал бы `IN ()`, что невалидный SQL.
     */
    private val historyFlow: Flow<List<PriceHistoryEntry>> = repository.observeProductIds()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else repository.observeHistoryForProducts(ids)
        }

    private val rows: Flow<List<ProductListRow>> =
        combine(repository.observeProducts(), historyFlow) { products, history ->
            val byProduct = history.groupBy { it.productId }
            products.map { product ->
                val series = buildPriceSeriesFromHistory(byProduct[product.id].orEmpty())
                ProductListRow(
                    product = product,
                    points = series.points.takeLast(SPARKLINE_POINTS),
                )
            }
        }

    private val _sort = MutableStateFlow(ProductSort.ADDED)
    val sort = _sort.asStateFlow()

    private val _filter = MutableStateFlow(ProductFilter.ALL)
    val filter = _filter.asStateFlow()

    /**
     * Что показывать в списке. Порядок и фильтр применяются здесь, а не на экране: иначе список
     * пересчитывался бы на каждой перезаписи композиции и показывал кадр со старым фильтром.
     */
    val visibleRows: StateFlow<List<ProductListRow>> = combine(rows, _sort, _filter) { source, sort, filter ->
        ProductListOrdering.apply(source, sort, filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Всего товаров — нужно, чтобы «2 из 9» не читалось как «в списке два товара». */
    val totalCount: StateFlow<Int> = rows.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun onSortSelected(value: ProductSort) {
        _sort.value = value
    }

    fun onFilterSelected(value: ProductFilter) {
        _filter.value = value
    }

    /** Сброс нужен отдельный: под фильтром список выглядит пустым, и это путает сильнее всего. */
    fun resetFilter() {
        _filter.value = ProductFilter.ALL
    }

    private companion object {
        /** Больше точек на карточке списка всё равно не различимы. */
        const val SPARKLINE_POINTS = 24
    }
}
