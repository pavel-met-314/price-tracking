package com.example.otsled.ui

import com.example.otsled.ui.challenge.BrowserCheckViewModel
import com.example.otsled.ui.products.AddProductViewModel
import com.example.otsled.ui.products.EditProductViewModel
import com.example.otsled.ui.products.ProductDetailViewModel
import com.example.otsled.ui.products.ProductListViewModel
import com.example.otsled.ui.settings.SettingsViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт фабрики ViewModel. Экран правки падал сразу при открытии: `viewModel(factory = ...)`
 * получил обычную фабрику, а ей нужен productId, и она ушла в «Unknown ViewModel» с
 * IllegalArgumentException. Тест держит именно границу: какие экраны обязаны строить себе обёртку.
 * Новый экран с id, про который забыли, попадёт в ID_BOUND_VIEW_MODELS — и проверка это заметит.
 */
class AppViewModelFactoryTest {

    @Test
    fun idBoundViewModelsAreNotCreatableByThePlainFactory() {
        assertTrue(AppViewModelFactory.requiresProductId(ProductDetailViewModel::class.java))
        assertTrue(AppViewModelFactory.requiresProductId(EditProductViewModel::class.java))
    }

    @Test
    fun screenViewModelsNeedNoId() {
        listOf(
            ProductListViewModel::class.java,
            AddProductViewModel::class.java,
            SettingsViewModel::class.java,
            BrowserCheckViewModel::class.java,
        ).forEach { modelClass ->
            assertFalse("${modelClass.simpleName} создаётся обычно", AppViewModelFactory.requiresProductId(modelClass))
        }
    }

    @Test
    fun unknownViewModelIsNotClaimedAsIdBound() {
        // Иначе «Unknown ViewModel» превратился бы в обманывающее «используй create<Name>ViewModel».
        assertFalse(AppViewModelFactory.requiresProductId(String::class.java))
    }
}
