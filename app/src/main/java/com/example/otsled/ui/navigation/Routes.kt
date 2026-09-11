package com.example.otsled.ui.navigation

object Routes {
    const val PRODUCT_LIST = "product_list"
    const val ADD_PRODUCT = "add_product"
    const val PRODUCT_DETAIL = "product_detail/{productId}"
    const val EDIT_PRODUCT = "edit_product/{productId}"
    const val SETTINGS = "settings"
    const val BROWSER_CHECK = "browser_check"

    fun productDetail(productId: Long) = "product_detail/$productId"

    fun editProduct(productId: Long) = "edit_product/$productId"
}
