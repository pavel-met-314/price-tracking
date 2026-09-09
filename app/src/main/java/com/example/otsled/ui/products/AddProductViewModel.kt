package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.data.parser.PricePageLoader
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.data.site.SiteSearchHit
import com.example.otsled.data.site.SiteSearchResult
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
    val parsedVariants: List<ParsedProductVariant> = emptyList(),
    val notifyOnAnyChange: Boolean = true,
    val notifyOnTargetReached: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchHits: List<SiteSearchHit> = emptyList(),
    val searchMessage: String? = null,
    /** true — страницу отдавал WebView: это медленный путь, и пользователю стоит это видеть. */
    val searchViaWebView: Boolean = false,
    val hasSearched: Boolean = false,
)

class AddProductViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val repository = container.productRepository
    private val pricePageLoader = container.pricePageLoader
    private val siteSearch = container.allureSiteSearch

    private val _uiState = MutableStateFlow(AddProductUiState())
    val uiState = _uiState.asStateFlow()

    fun onUrlChange(value: String) {
        _uiState.update { it.copy(url = value, errorMessage = null) }
    }

    fun onSearchQueryChange(value: String) {
        _uiState.update { it.copy(searchQuery = value, searchMessage = null) }
    }

    fun clearSearch() {
        _uiState.update {
            it.copy(searchQuery = "", searchHits = emptyList(), searchMessage = null, searchViaWebView = false)
        }
    }

    /**
     * Поиск по названию. Ошибкой считаем только ситуацию, когда страница не ответила вообще:
     * пустая выдача — нормальный результат («такого товара нет»), иначе пользователь видел бы
     * «ошибку» там, где просто нет совпадений.
     */
    fun runSearch() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchMessage = null, searchHits = emptyList()) }
            val result = siteSearch.search(query)
            _uiState.update { state ->
                when (result) {
                    is SiteSearchResult.Success -> state.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchHits = result.hits,
                        searchViaWebView = result.viaWebView,
                        searchMessage = null,
                    )
                    is SiteSearchResult.Error -> state.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchHits = emptyList(),
                        searchViaWebView = false,
                        searchMessage = result.message,
                    )
                }
            }
        }
    }

    /**
     * Выбор строки поиска: подставляем ссылку и сразу зовём проверку, чтобы до сохранения
     * человек видел цены по объёмам, а не «купил» вслепую.
     */
    fun useSearchHit(hit: SiteSearchHit) {
        _uiState.update {
            it.copy(
                url = hit.url,
                title = hit.title,
                errorMessage = null,
                parsedVariants = emptyList(),
            )
        }
        checkNow()
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
        if (!ProductUrlNormalizer.isSupportedUrl(url)) {
            _uiState.update { it.copy(errorMessage = PricePageLoader.URL_HINT) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            applyParseResult(pricePageLoader.fetchAndParse(url))
        }
    }

    fun saveProduct() {
        val state = _uiState.value
        val url = state.url.trim()
        if (!ProductUrlNormalizer.isSupportedUrl(url)) {
            _uiState.update { it.copy(errorMessage = PricePageLoader.URL_HINT) }
            return
        }

        val normalizedUrl = ProductUrlNormalizer.normalize(url) ?: url

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            var title = state.title
            var variants = state.parsedVariants

            if (title.isBlank() || variants.isEmpty()) {
                when (val result = pricePageLoader.fetchAndParse(normalizedUrl)) {
                    is ParseResult.Success -> {
                        title = result.title
                        variants = result.variants
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
            // minOf на пустом списке бросает NoSuchElementException — вариант «страница есть,
            // цен нет» вполне реален, поэтому проверяем явно.
            val minPrice = variants.minOfOrNull { it.price }
            if (minPrice == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = PricePageLoader.NO_PRICES_HINT) }
                return@launch
            }
            val product = TrackedProduct(
                url = normalizedUrl,
                title = title,
                targetPrice = targetPrice,
                lastPrice = minPrice,
                lastCheckedAt = now,
                notifyOnAnyChange = state.notifyOnAnyChange,
                notifyOnTargetReached = state.notifyOnTargetReached,
            )

            val id = repository.insertProduct(product)
            val savedVariants = repository.replaceVariants(id, variants, now)
            savedVariants.forEach { variant ->
                repository.insertHistory(
                    PriceHistoryEntry(
                        productId = id,
                        variantId = variant.id,
                        volumeLabel = variant.displayName(),
                        price = variant.lastPrice,
                        checkedAt = now,
                    ),
                )
            }

            _uiState.update { it.copy(isLoading = false, saved = true) }
        }
    }

    private fun applyParseResult(result: ParseResult) {
        _uiState.update {
            when (result) {
                is ParseResult.Success -> it.copy(
                    isLoading = false,
                    title = result.title,
                    parsedVariants = result.variants,
                )
                is ParseResult.Error -> it.copy(
                    isLoading = false,
                    errorMessage = result.message,
                )
            }
        }
    }
}
