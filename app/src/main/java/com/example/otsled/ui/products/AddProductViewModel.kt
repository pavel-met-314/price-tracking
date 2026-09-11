package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.data.parser.PricePageLoader
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.domain.ProductEditing
import com.example.otsled.data.site.SiteSearchHit
import com.example.otsled.data.site.SiteSearchResult
import com.example.otsled.data.site.SiteSearchTracking
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.model.PriceCheckLog
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
    /** Диагноз разбора (что пробовали и что получили). Именно его просят прислать при «пусто на телефоне». */
    val searchNote: String? = null,
    /** true — ошибка временная (проверка браузера или сеть), имеет смысл нажать «Повторить». */
    val searchRetryable: Boolean = false,
    /**
     * URL строки выдачи -> id уже отслеживаемого товара (только совпавшие строки). Нужно, чтобы
     * повторный наход того же товара не выглядел как «добавьте заново»: вставка с тем же URL
     * затирала бы цель и настройки уведомлений.
     */
    /** id совпавших товаров, которые лежат в архиве: подпись в выдаче отличается от обычной. */
    val searchArchivedIds: Set<Long> = emptySet(),
    val searchTrackedIds: Map<String, Long> = emptyMap(),
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
            it.copy(
                searchQuery = "",
                searchHits = emptyList(),
                searchMessage = null,
                searchViaWebView = false,
                searchTrackedIds = emptyMap(),
                searchArchivedIds = emptySet(),
            )
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
            _uiState.update {
                it.copy(
                    isSearching = true,
                    searchMessage = null,
                    searchHits = emptyList(),
                    searchNote = null,
                    searchRetryable = false,
                )
            }
            val result = siteSearch.search(query)
            logSearchOutcome(query, result)
            // Спрашиваем свой список после поиска, а не до: поиск может длиться десятки секунд,
            // и за это время товар успели удалить или добавить вручную.
            // Список берём полный (включая архив), а не «активные для проверок»: архивный товар —
            // всё ещё отслеживаемая страница, и «добавить» его повторно затёр бы archivedAt и
            // отвязал историю цен (ключ в таблице — URL, REPLACE перечёркивает запись целиком).
            val products = if (result is SiteSearchResult.Success) {
                repository.getAllProducts()
            } else {
                emptyList()
            }
            val tracked = SiteSearchTracking.trackedIdsByUrl(products)
            val archived = SiteSearchTracking.archivedIds(products)
            val hits = (result as? SiteSearchResult.Success)?.hits.orEmpty()
            val matchedIds = SiteSearchTracking.resolve(hits, tracked)
            _uiState.update { state ->
                when (result) {
                    is SiteSearchResult.Success -> state.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchHits = result.hits,
                        searchViaWebView = result.viaWebView,
                        searchMessage = null,
                        searchRetryable = false,
                        searchTrackedIds = matchedIds,
                        searchArchivedIds = matchedIds.values.filter { archived.contains(it) }.toSet(),
                        searchNote = combineNotes(
                            result.note,
                            SiteSearchTracking.report(hits, tracked, archived.size),
                        ),
                    )
                    is SiteSearchResult.Error -> state.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchHits = emptyList(),
                        searchViaWebView = false,
                        searchMessage = result.message,
                        searchNote = result.note,
                        searchTrackedIds = emptyMap(),
                        searchArchivedIds = emptySet(),
                        searchRetryable = result.kind == SiteSearchResult.Kind.BOT_CHALLENGE ||
                            result.kind == SiteSearchResult.Kind.NETWORK,
                    )
                }
            }
        }
    }

    /**
     * Исход поиска попадает в тот же «Журнал проверок», что и фоновые проверки цен. Разметку
     * страницы поиска живьём мы не видим, поэтому «на телефоне пусто» без записи в журнале
     * превращается в гадание: HTTP это был или WebView, и сколько строк реально разобрано.
     */
    private suspend fun logSearchOutcome(query: String, result: SiteSearchResult) {
        val now = System.currentTimeMillis()
        val entry = when (result) {
            is SiteSearchResult.Success -> PriceCheckLog(
                productId = null,
                status = PriceCheckLog.STATUS_OK,
                kind = "SEARCH",
                source = if (result.viaWebView) "WEBVIEW" else "HTTP",
                message = "Поиск «${result.query}»: строк ${result.hits.size}" + result.noteSuffix(),
                variantsCount = result.hits.size,
                createdAt = now,
            )

            is SiteSearchResult.Error -> PriceCheckLog(
                productId = null,
                status = PriceCheckLog.STATUS_ERROR,
                kind = "SEARCH_${result.kind.name}",
                source = "",
                message = "Поиск «${query}»: ${result.message}" + result.noteSuffix(),
                variantsCount = 0,
                createdAt = now,
            )
        }
        repository.logCheck(entry)
    }

    /** Диагноз разбора и результат сопоставления со списком — одной строкой: их читают вместе. */
    private fun combineNotes(note: String?, matchReport: String): String? = when {
        matchReport.isBlank() -> note
        note.isNullOrBlank() -> matchReport
        else -> "$note | $matchReport"
    }

    /**
     * Диагноз приклеивается к сообщению журнала через « | »: у журнала одна строку на запись,
     * и человек читает её целиком — заводить отдельную запись ради диагностики не за чем.
     */
    private fun SiteSearchResult.noteSuffix(): String {
        val details = when (this) {
            is SiteSearchResult.Success -> note
            is SiteSearchResult.Error -> note
        }
        return if (details.isNullOrBlank()) "" else " | $details"
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

            // Тот же разбор, что и на экране правки: «1 200» и «1200,50 ₽» — валидные записи,
            // а toDoubleOrNull() молча их терял, и цель не ставилась.
            val targetPrice = ProductEditing.parseTarget(state.targetPrice)

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
