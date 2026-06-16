package com.example.otsled.domain

import android.content.Context
import com.example.otsled.R
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.data.parser.PricePageLoader
import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.data.repository.ProductRepository
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.ProductVariant
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.notification.PriceNotificationManager

class PriceCheckUseCase(
    private val context: Context,
    private val productRepository: ProductRepository,
    private val pricePageLoader: PricePageLoader,
    private val notificationManager: PriceNotificationManager,
) {
    suspend fun checkProduct(product: TrackedProduct): ParseResult {
        val normalizedUrl = ProductUrlNormalizer.normalize(product.url) ?: product.url
        val result = pricePageLoader.fetchAndParse(normalizedUrl)

        if (result is ParseResult.Success) {
            handleSuccessfulCheck(product, result.title, result.variants)
        }

        return result
    }

    suspend fun checkAllActiveProducts() {
        productRepository.getActiveProducts().forEach { checkProduct(it) }
    }

    private suspend fun handleSuccessfulCheck(
        product: TrackedProduct,
        newTitle: String,
        parsedVariants: List<ParsedProductVariant>,
    ) {
        val now = System.currentTimeMillis()
        val previousVariants = productRepository.getVariants(product.id).associateBy { it.variantKey }
        val savedVariants = productRepository.replaceVariants(product.id, parsedVariants, now)
        val previousMinPrice = product.lastPrice
        val newMinPrice = parsedVariants.minOf { it.price }

        savedVariants.forEach { variant ->
            val previous = previousVariants[variant.variantKey]
            if (previous == null || previous.lastPrice != variant.lastPrice) {
                productRepository.insertHistory(
                    PriceHistoryEntry(
                        productId = product.id,
                        variantId = variant.id,
                        volumeLabel = variant.displayName(),
                        price = variant.lastPrice,
                        checkedAt = now,
                    ),
                )
            }

            maybeNotifyVariantChange(product, newTitle, previous, variant)
        }

        productRepository.updateProduct(
            product.copy(
                title = newTitle.ifBlank { product.title },
                lastPrice = newMinPrice,
                lastCheckedAt = now,
            ),
        )

        maybeNotifyTargetReached(product, newTitle, previousMinPrice, newMinPrice, parsedVariants)
    }

    private fun maybeNotifyVariantChange(
        product: TrackedProduct,
        title: String,
        previous: ProductVariant?,
        current: ProductVariant,
    ) {
        if (!product.notifyOnAnyChange) return
        if (previous == null || previous.lastPrice == current.lastPrice) return

        notificationManager.showPriceAlert(
            productId = product.id,
            title = context.getString(R.string.price_changed_title),
            message = context.getString(
                R.string.price_changed_variant_message,
                title,
                current.displayName(),
                formatPrice(previous.lastPrice),
                formatPrice(current.lastPrice),
            ),
        )
    }

    private fun maybeNotifyTargetReached(
        product: TrackedProduct,
        title: String,
        previousMinPrice: Double?,
        newMinPrice: Double,
        variants: List<ParsedProductVariant>,
    ) {
        val targetPrice = product.targetPrice ?: return
        if (!product.notifyOnTargetReached) return

        val reachedVariant = variants
            .filter { it.price <= targetPrice }
            .minByOrNull { it.price }
            ?: return

        val wasAlreadyReached = previousMinPrice != null && previousMinPrice <= targetPrice
        if (wasAlreadyReached) return

        notificationManager.showPriceAlert(
            productId = product.id,
            title = context.getString(R.string.target_reached_title),
            message = context.getString(
                R.string.target_reached_variant_message,
                title,
                reachedVariant.displayName(),
                formatPrice(reachedVariant.price),
                formatPrice(targetPrice),
            ),
        )
    }

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) {
            price.toLong().toString()
        } else {
            price.toString()
        }
    }
}
