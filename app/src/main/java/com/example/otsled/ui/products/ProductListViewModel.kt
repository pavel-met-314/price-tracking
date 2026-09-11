package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.VariantDrop
import com.example.otsled.domain.VariantPriceChange
import com.example.otsled.domain.VariantPriceChanges
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.TrackedProduct
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Товар и то, что делает его цена. Не «цена товара вообще», а по каждому объёму отдельно:
 * флакон 100 мл и пробник 2 мл на одной шкале дают рост там, где цена падала.
 */
data class ProductListRow(
    val product: TrackedProduct,
    /** Изменения последней проверки — по одному на каждый объём, у которого цена поменялась. */
    val changes: List<VariantPriceChange> = emptyList(),
    /** Самое сильное падение среди объёмов за наблюдаемую историю — для порядка «упали сильнее всего». */
    val drop: VariantDrop? = null,
)

/**
 * Всё, что нужно экрану: показываемые строки, сколько товаров всего и выбранные порядок с
 * фильтром — одним состоянием. Отдельные потоки на строки и на счётчик разъезжаются на первом
 * кадре (счётчик ещё 0, строки уже есть), и экран выглядел бы как список без чипов фильтра.
 */
data class ProductListUiState(
    val rows: List<ProductListRow> = emptyList(),
    val totalCount: Int = 0,
    val sort: ProductSort = ProductSort.ADDED,
    val filter: ProductFilter = ProductFilter.ALL,
) {
    val isEmptyList: Boolean get() = totalCount == 0
    val isFilterEmpty: Boolean get() = !isEmptyList && rows.isEmpty()
    val showsAll: Boolean get() = filter == ProductFilter.ALL
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
                val entries = byProduct[product.id].orEmpty()
                ProductListRow(
                    product = product,
                    changes = VariantPriceChanges.atLastCheck(entries, product.lastSuccessAt),
                    drop = VariantPriceChanges.bestDrop(entries),
                )
            }
        }

    private val _sort = MutableStateFlow(ProductSort.ADDED)
    private val _filter = MutableStateFlow(ProductFilter.ALL)

    val state: StateFlow<ProductListUiState> = combine(rows, _sort, _filter) { source, sort, filter ->
        ProductListUiState(
            rows = ProductListOrdering.apply(source, sort, filter),
            // «из N» — про текущий режим: с архивом в общем счёте заголовок врал бы, что
            // «показано 3 из 40», хотя сорок товаров пользователь сюда не звал.
            totalCount = ProductListOrdering.countInCurrentView(source, filter),
            sort = sort,
            filter = filter,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProductListUiState())

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
}
