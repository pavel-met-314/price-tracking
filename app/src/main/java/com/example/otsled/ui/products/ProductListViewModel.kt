package com.example.otsled.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.model.TrackedProduct
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class ProductListViewModel(
    container: AppContainer,
) : ViewModel() {
    private val repository = container.productRepository

    val products = repository.observeProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
