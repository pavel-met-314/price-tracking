package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.PricePoint
import com.example.otsled.domain.buildPriceSeriesFromHistory
import com.example.otsled.domain.model.TrackedProduct
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val historyFlow = repository.observeProductIds()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else repository.observeHistoryForProducts(ids)
        }

    val rows = combine(repository.observeProducts(), historyFlow) { products, history ->
        val byProduct = history.groupBy { it.productId }
        products.map { product ->
            val series = buildPriceSeriesFromHistory(byProduct[product.id].orEmpty())
            ProductListRow(
                product = product,
                points = series.points.takeLast(SPARKLINE_POINTS),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private companion object {
        /** Больше точек на карточке списка всё равно не различимы. */
        const val SPARKLINE_POINTS = 24
    }
}
