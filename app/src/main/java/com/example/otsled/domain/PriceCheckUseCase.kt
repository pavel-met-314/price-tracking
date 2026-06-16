package com.example.otsled.domain

import android.content.Context
import com.example.otsled.R
import com.example.otsled.data.parser.AllureParfumPriceParser
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.data.parser.WebViewPriceFetcher
import com.example.otsled.data.repository.ProductRepository
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.notification.PriceNotificationManager

class PriceCheckUseCase(
    private val context: Context,
    private val productRepository: ProductRepository,
    private val priceParser: AllureParfumPriceParser,
    private val notificationManager: PriceNotificationManager,
    private val webViewPriceFetcher: WebViewPriceFetcher,
) {
    suspend fun checkProduct(product: TrackedProduct): ParseResult {
        var result = priceParser.fetchAndParse(product.url)
        if (result is ParseResult.Error && result.message.contains("WebView")) {
            val html = webViewPriceFetcher.fetchHtml(product.url)
            result = if (html.isNullOrBlank()) {
                ParseResult.Error("Не удалось загрузить страницу через WebView")
            } else {
                priceParser.parseHtml(html, product.url)
            }
        }

        if (result is ParseResult.Success) {
            handleSuccessfulCheck(product, result.price, result.title)
        }

        return result
    }

    suspend fun checkAllActiveProducts() {
        productRepository.getActiveProducts().forEach { checkProduct(it) }
    }

    private suspend fun handleSuccessfulCheck(
        product: TrackedProduct,
        newPrice: Double,
        newTitle: String,
    ) {
        val now = System.currentTimeMillis()
        val previousPrice = product.lastPrice

        productRepository.insertHistory(
            PriceHistoryEntry(
                productId = product.id,
                price = newPrice,
                checkedAt = now,
            ),
        )

        val updated = product.copy(
            title = newTitle.ifBlank { product.title },
            lastPrice = newPrice,
            lastCheckedAt = now,
        )
        productRepository.updateProduct(updated)

        if (previousPrice != null && previousPrice != newPrice && product.notifyOnAnyChange) {
            notificationManager.showPriceAlert(
                productId = product.id,
                title = context.getString(R.string.price_changed_title),
                message = context.getString(
                    R.string.price_changed_message,
                    updated.title,
                    formatPrice(previousPrice),
                    formatPrice(newPrice),
                ),
            )
        }

        val targetPrice = product.targetPrice
        if (
            targetPrice != null &&
            product.notifyOnTargetReached &&
            newPrice <= targetPrice &&
            (previousPrice == null || previousPrice > targetPrice)
        ) {
            notificationManager.showPriceAlert(
                productId = product.id,
                title = context.getString(R.string.target_reached_title),
                message = context.getString(
                    R.string.target_reached_message,
                    updated.title,
                    formatPrice(newPrice),
                    formatPrice(targetPrice),
                ),
            )
        }
    }

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) {
            price.toLong().toString()
        } else {
            price.toString()
        }
    }
}
