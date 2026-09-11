package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.EditField
import com.example.otsled.domain.EditReason
import com.example.otsled.domain.ProductEditDraft
import com.example.otsled.domain.ProductEditing
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.util.PriceFormatter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Правка отслеживаемого товара. Правила валидации живут в `domain/ProductEditing` — здесь только
 * снятие состояния и запись: проверять ссылку и цену в двух местах значит однажды разойтись.
 */
data class EditUiState(
    val problems: Map<EditField, EditReason> = emptyMap(),
    /** Ссылка ведёт на другую страницу: экран обязан предупредить до сохранения, а не после. */
    val pageChanged: Boolean = false,
    val saved: Boolean = false,
    val isChecking: Boolean = false,
    val checkNote: String? = null,
)

class EditProductViewModel(
    private val container: AppContainer,
    private val productId: Long,
) : ViewModel() {
    private val repository = container.productRepository
    private val priceCheckUseCase = container.priceCheckUseCase

    val product: StateFlow<TrackedProduct?> = repository.observeProduct(productId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Начальные значения полей: экран заполняет их один раз, когда товар дошёл из базы. */
    val initialDraft: StateFlow<ProductEditDraft?> = repository.observeProduct(productId)
        .map { it?.toDraft() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    fun draftChanged(draft: ProductEditDraft) {
        val current = product.value ?: return
        val result = ProductEditing.validate(draft, current)
        // Предупреждение показываем сразу, а не по нажатию «сохранить»: человек должен видеть
        // последствия до того, как истории станет меньше.
        _uiState.update { it.copy(problems = result.problems, pageChanged = result.edit?.pageChanged == true) }
    }

    fun save(draft: ProductEditDraft, checkAfter: Boolean, onSaved: () -> Unit) {
        val current = product.value ?: return
        val result = ProductEditing.validate(draft, current)
        val edit = result.edit
        _uiState.update { it.copy(problems = result.problems) }
        if (edit == null) return

        viewModelScope.launch {
            repository.applyEdit(current.id, edit)
            if (checkAfter) {
                _uiState.update { it.copy(isChecking = true, checkNote = null) }
                val refreshed = repository.getProduct(current.id)
                if (refreshed != null) {
                    // Диалог с сайтом мог не получиться — это надо показать здесь же, а не
                    // отправлять человека гадать, применилась ли новая ссылка.
                    when (val outcome = priceCheckUseCase.checkProduct(refreshed)) {
                        is ParseResult.Success -> _uiState.update {
                            it.copy(
                                checkNote = container.applicationContext.getString(
                                    com.example.otsled.R.string.edit_check_ok,
                                    outcome.variants.size,
                                ),
                            )
                        }

                        is ParseResult.Error -> _uiState.update {
                            it.copy(
                                checkNote = container.applicationContext.getString(
                                    com.example.otsled.R.string.edit_check_failed,
                                    outcome.message,
                                ),
                            )
                        }
                    }
                }
                _uiState.update { it.copy(isChecking = false) }
            }
            _uiState.update { it.copy(saved = true) }
            onSaved()
        }
    }

    private fun TrackedProduct.toDraft() = ProductEditDraft(
        title = titleOverride.orEmpty(),
        url = url,
        targetPrice = targetPrice?.let { PriceFormatter.formatPrice(it) }.orEmpty(),
        notifyOnAnyChange = notifyOnAnyChange,
        notifyOnTargetReached = notifyOnTargetReached,
    )
}
