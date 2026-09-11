package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.PriceSeries
import com.example.otsled.domain.buildPriceSeriesFromHistory
import com.example.otsled.domain.model.PriceHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * График и список истории — один и тот же набор записей, чтобы выбранный объём фильтровал
 * оба блока одинаково. Иначе цифры под графиком начинают спорить со списком под ним.
 */
data class PriceOverview(
    val series: PriceSeries,
    val history: List<PriceHistoryEntry>,
)

data class ProductDetailUiState(
    val isChecking: Boolean = false,
    val errorMessage: String? = null,
    /** Экран закрывают и удаление, и архив: в обоих случаях смотреть здесь больше не на что. */
    val closed: Boolean = false,
)

class ProductDetailViewModel(
    private val container: AppContainer,
    private val productId: Long,
) : ViewModel() {
    private val repository = container.productRepository
    private val priceCheckUseCase = container.priceCheckUseCase

    val product = repository.observeProduct(productId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val variants = repository.observeVariants(productId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val history = repository.observeHistory(productId)

    /** Что выбрал пользователь; null — «дай самый дешёвый отслеживаемый», см. [selectedVariant]. */
    private val requestedVariantId = MutableStateFlow<Long?>(null)

    /**
     * Объём, который сейчас показан. График всегда про один объём: «все объёмы вместе» — это
     * сравнение 2 мл с 100 мл, и линия на таком ряду врёт. Пока пользователь ничего не выбирал,
     * показываем самый дешёвый отслеживаемый — его же магазин выставляет в цене «от».
     */
    val selectedVariant = combine(requestedVariantId, variants, history) { requested, all, entries ->
        val tracked = all.filter { it.isTracked }
        tracked.firstOrNull { it.id == requested }?.id
            ?: tracked.minByOrNull { it.lastPrice }?.id
            ?: entries.mapNotNull { it.variantId }.firstOrNull()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null,
    )

    val overview = combine(history, selectedVariant) { entries, variantId ->
        val filtered = if (variantId == null) emptyList() else entries.filter { it.variantId == variantId }
        PriceOverview(
            series = if (variantId == null) PriceSeries.EMPTY else buildPriceSeriesFromHistory(filtered, variantId),
            history = filtered,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PriceOverview(PriceSeries.EMPTY, emptyList()),
    )

    private val _uiState = MutableStateFlow(ProductDetailUiState())
    val uiState = _uiState.asStateFlow()

    fun selectVariant(variantId: Long) {
        requestedVariantId.update { variantId }
    }

    fun checkNow() {
        val current = product.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null) }
            when (val result = priceCheckUseCase.checkProduct(current)) {
                is ParseResult.Success -> _uiState.update { it.copy(isChecking = false) }
                is ParseResult.Error -> _uiState.update {
                    it.copy(isChecking = false, errorMessage = result.message)
                }
            }
        }
    }

    fun deleteProduct() {
        val current = product.value ?: return
        viewModelScope.launch {
            repository.deleteProduct(current)
            _uiState.update { it.copy(closed = true) }
        }
    }

    /**
     * Архив вместо удаления: записи, цены и история остаются, проверок нет. Отдельно от паузы —
     * «надоело» и «посмотрю позже» решают разные вещи, и одно не должно включать другое.
     */
    fun moveToArchive() {
        val current = product.value ?: return
        viewModelScope.launch {
            repository.setArchived(current.id, System.currentTimeMillis())
            _uiState.update { it.copy(closed = true) }
        }
    }

    fun restoreFromArchive() {
        val current = product.value ?: return
        viewModelScope.launch { repository.setArchived(current.id, at = null) }
    }

    fun togglePaused() {
        val current = product.value ?: return
        viewModelScope.launch { repository.setActive(current.id, active = !current.isActive) }
    }
}
