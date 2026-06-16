package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.parser.AllureParfumPriceParser
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.TrackedProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddProductUiState(
    val url: String = "",
    val targetPrice: String = "",
    val title: String = "",
    val parsedPrice: Double? = null,
    val notifyOnAnyChange: Boolean = true,
    val notifyOnTargetReached: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
)

class AddProductViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val repository = container.productRepository
    private val priceParser = container.priceParser()
    private val webViewFetcher = container.webViewPriceFetcher()
    private val priceCheckUseCase = container.priceCheckUseCase()

    private val _uiState = MutableStateFlow(AddProductUiState())
    val uiState = _uiState.asStateFlow()

    fun onUrlChange(value: String) {
        _uiState.update { it.copy(url = value, errorMessage = null) }
    }

    fun onTargetPriceChange(value: String) {
        _uiState.update { it.copy(targetPrice = value, errorMessage = null) }
    }

    fun onNotifyOnChangeToggle(value: Boolean) {
        _uiState.update { it.copy(notifyOnAnyChange = value) }
    }

    fun onNotifyOnTargetToggle(value: Boolean) {
        _uiState.update { it.copy(notifyOnTargetReached = value) }
    }

    fun checkNow() {
        val url = _uiState.value.url.trim()
        if (!priceParser.isSupportedUrl(url)) {
            _uiState.update { it.copy(errorMessage = "Укажите ссылку на allureparfum.ru") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = parseUrl(url)
            _uiState.update {
                when (result) {
                    is ParseResult.Success -> it.copy(
                        isLoading = false,
                        title = result.title,
                        parsedPrice = result.price,
                    )
                    is ParseResult.Error -> it.copy(
                        isLoading = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun saveProduct() {
        val state = _uiState.value
        val url = state.url.trim()
        if (!priceParser.isSupportedUrl(url)) {
            _uiState.update { it.copy(errorMessage = "Укажите ссылку на allureparfum.ru") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            var title = state.title
            var price = state.parsedPrice

            if (title.isBlank() || price == null) {
                when (val result = parseUrl(url)) {
                    is ParseResult.Success -> {
                        title = result.title
                        price = result.price
                    }
                    is ParseResult.Error -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                        return@launch
                    }
                }
            }

            val targetPrice = state.targetPrice.trim()
                .replace(',', '.')
                .toDoubleOrNull()

            val now = System.currentTimeMillis()
            val product = TrackedProduct(
                url = url,
                title = title,
                targetPrice = targetPrice,
                lastPrice = price,
                lastCheckedAt = now,
                notifyOnAnyChange = state.notifyOnAnyChange,
                notifyOnTargetReached = state.notifyOnTargetReached,
            )

            val id = repository.insertProduct(product)
            if (price != null) {
                repository.insertHistory(
                    PriceHistoryEntry(
                        productId = id,
                        price = price,
                        checkedAt = now,
                    ),
                )
            }

            _uiState.update { it.copy(isLoading = false, saved = true) }
        }
    }

    private suspend fun parseUrl(url: String): ParseResult {
        var result = priceParser.fetchAndParse(url)
        if (result is ParseResult.Error && result.message.contains("WebView")) {
            val html = webViewFetcher.fetchHtml(url)
            result = if (html.isNullOrBlank()) {
                ParseResult.Error("Не удалось загрузить страницу")
            } else {
                priceParser.parseHtml(html, url)
            }
        }
        return result
    }
}
