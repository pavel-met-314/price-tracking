package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.TrackedProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProductDetailUiState(
    val isChecking: Boolean = false,
    val errorMessage: String? = null,
    val deleted: Boolean = false,
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

    val history = repository.observeHistory(productId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(ProductDetailUiState())
    val uiState = _uiState.asStateFlow()

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
            _uiState.update { it.copy(deleted = true) }
        }
    }
}
