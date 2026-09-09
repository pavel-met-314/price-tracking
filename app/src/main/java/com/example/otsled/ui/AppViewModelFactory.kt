package com.example.otsled.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.otsled.di.AppContainer

class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(com.example.otsled.ui.products.ProductListViewModel::class.java) ->
                com.example.otsled.ui.products.ProductListViewModel(container) as T
            modelClass.isAssignableFrom(com.example.otsled.ui.products.AddProductViewModel::class.java) ->
                com.example.otsled.ui.products.AddProductViewModel(container) as T
            modelClass.isAssignableFrom(com.example.otsled.ui.products.ProductDetailViewModel::class.java) ->
                throw IllegalArgumentException("ProductDetailViewModel requires productId")
            modelClass.isAssignableFrom(com.example.otsled.ui.settings.SettingsViewModel::class.java) ->
                com.example.otsled.ui.settings.SettingsViewModel(container) as T
            modelClass.isAssignableFrom(com.example.otsled.ui.challenge.BrowserCheckViewModel::class.java) ->
                com.example.otsled.ui.challenge.BrowserCheckViewModel(container) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }

    fun createProductDetailViewModel(productId: Long): com.example.otsled.ui.products.ProductDetailViewModel {
        return com.example.otsled.ui.products.ProductDetailViewModel(container, productId)
    }
}
