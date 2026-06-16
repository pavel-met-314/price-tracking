package com.example.otsled.ui.navigation

object Routes {
    const val PRODUCT_LIST = "product_list"
    const val ADD_PRODUCT = "add_product"
    const val PRODUCT_DETAIL = "product_detail/{productId}"
    const val SETTINGS = "settings"

    fun productDetail(productId: Long) = "product_detail/$productId"
}
