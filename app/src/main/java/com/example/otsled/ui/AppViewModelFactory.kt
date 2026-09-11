package com.example.otsled.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.otsled.di.AppContainer

class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        // ViewModel, которому нужен id товара, здесь создаваться не может: у фабрики нет этого id.
        // Отдельная ветка с внятным текстом — чтобы «экран падает при открытии» читалось в логе
        // как «забыл обёртку у экрана», а не как «Unknown ViewModel».
        if (requiresProductId(modelClass)) {
            throw IllegalArgumentException(
                "${modelClass.simpleName} требует productId — создавай его через create<Name>ViewModel(productId)",
            )
        }
        return when {
            modelClass.isAssignableFrom(com.example.otsled.ui.products.ProductListViewModel::class.java) ->
                com.example.otsled.ui.products.ProductListViewModel(container) as T
            modelClass.isAssignableFrom(com.example.otsled.ui.products.AddProductViewModel::class.java) ->
                com.example.otsled.ui.products.AddProductViewModel(container) as T
            modelClass.isAssignableFrom(com.example.otsled.ui.settings.SettingsViewModel::class.java) ->
                com.example.otsled.ui.settings.SettingsViewModel(container) as T
            modelClass.isAssignableFrom(com.example.otsled.ui.challenge.BrowserCheckViewModel::class.java) ->
                com.example.otsled.ui.challenge.BrowserCheckViewModel(container) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }

    companion object {
        /** ViewModel, которым нужен id: их создаёт только обёртка на экране, не эта фабрика. */
        val ID_BOUND_VIEW_MODELS: Set<Class<out ViewModel>> = setOf(
            com.example.otsled.ui.products.ProductDetailViewModel::class.java,
            com.example.otsled.ui.products.EditProductViewModel::class.java,
        )

        fun requiresProductId(modelClass: Class<*>): Boolean =
            ID_BOUND_VIEW_MODELS.any { it.isAssignableFrom(modelClass) }
    }

    fun createProductDetailViewModel(productId: Long): com.example.otsled.ui.products.ProductDetailViewModel {
        return com.example.otsled.ui.products.ProductDetailViewModel(container, productId)
    }

    /** Правка — отдельная ViewModel того же товара: у экрана свои поля и свой результат сохранения. */
    fun createEditProductViewModel(productId: Long): com.example.otsled.ui.products.EditProductViewModel {
        return com.example.otsled.ui.products.EditProductViewModel(container, productId)
    }
}
