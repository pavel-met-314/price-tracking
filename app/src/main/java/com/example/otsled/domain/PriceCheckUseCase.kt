package com.example.otsled.domain

import android.content.Context
import com.example.otsled.R
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.data.parser.PricePageLoader
import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.data.repository.ProductRepository
import com.example.otsled.domain.model.PriceCheckLog
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.ProductVariant
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.notification.PriceNotificationManager

/** Итог прогона по всем товарам — нужен воркеру, чтобы решить, ретраить ли пакет. */
data class CheckOutcome(
    val total: Int,
    val succeeded: Int,
    val retryableFailures: Int,
    /** Товары, до которых не дошла очередь: цикл упёрся во временной бюджет. */
    val skippedByBudget: Int = 0,
) {
    val failed: Int get() = total - succeeded - skippedByBudget

    /**
     * Повтор имеет смысл, только если не удалось ничего: частичный успех означает, что сайт
     * отвечает, а отдельный товар сломался по своей причине (снят с продажи, удалена страница).
     * Пропущенные бюджетом товары тоже требуют повтора — иначе они будут ждать следующего
     * часового интервала без всякой причины.
     */
    val shouldRetry: Boolean
        get() = total > 0 && succeeded == 0 && (retryableFailures > 0 || skippedByBudget > 0)
}

class PriceCheckUseCase(
    private val context: Context,
    private val productRepository: ProductRepository,
    private val pricePageLoader: PricePageLoader,
    private val notificationManager: PriceNotificationManager,
) {
    private companion object {
        /** Отдельный тег, чтобы «цель достигнута» не затиралась уведомлением об изменении цены. */
        const val TARGET_NOTIFICATION_TAG = "target-price"

        /** Сколько разрешено проверять цены в одном фоновом цикле. */
        const val MAX_CYCLE_MILLIS = 6 * 60_000L
    }
    suspend fun checkProduct(product: TrackedProduct): ParseResult {
        val normalizedUrl = ProductUrlNormalizer.normalize(product.url) ?: product.url
        val result = pricePageLoader.fetchAndParse(normalizedUrl)
        val now = System.currentTimeMillis()

        when (result) {
            is ParseResult.Success -> handleSuccessfulCheck(product, result, now)
            is ParseResult.Error -> handleFailedCheck(product, result, now)
        }

        return result
    }

    /**
     * Прогон по всем товарам с временным бюджетом. WebView-путь может занимать десятки секунд
     * на товар, и без ограничения один цикл фонового обновления растягивается на полчаса
     * с включённым экраном и сетью — при большом списке это заметно по батарее.
     */
    suspend fun checkAllActiveProducts(): CheckOutcome {
        val products = productRepository.getActiveProducts()
        val deadline = System.currentTimeMillis() + MAX_CYCLE_MILLIS
        var succeeded = 0
        var retryable = 0
        var skipped = 0
        var budgetExceeded = false

        products.forEach { product ->
            if (budgetExceeded) {
                skipped++
                return@forEach
            }
            if (System.currentTimeMillis() > deadline) {
                budgetExceeded = true
                skipped++
                return@forEach
            }
            when (val result = checkProduct(product)) {
                is ParseResult.Success -> succeeded++
                is ParseResult.Error -> if (result.isRetryable) retryable++
            }
        }

        return CheckOutcome(
            total = products.size,
            succeeded = succeeded,
            retryableFailures = retryable,
            skippedByBudget = skipped,
        )
    }

    private suspend fun handleSuccessfulCheck(
        product: TrackedProduct,
        result: ParseResult.Success,
        now: Long,
    ) {
        val newTitle = result.title.ifBlank { product.title }
        val parsedVariants = result.variants
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

        productRepository.markCheckSuccess(
            productId = product.id,
            title = newTitle,
            lastPrice = newMinPrice,
            checkedAt = now,
        )

        maybeNotifyTargetReached(product, newTitle, previousMinPrice, newMinPrice, parsedVariants)

        productRepository.logCheck(
            PriceCheckLog(
                productId = product.id,
                status = PriceCheckLog.STATUS_OK,
                kind = "",
                source = result.source.name,
                message = null,
                variantsCount = parsedVariants.size,
                createdAt = now,
            ),
        )
    }

    private suspend fun handleFailedCheck(
        product: TrackedProduct,
        error: ParseResult.Error,
        now: Long,
    ) {
        productRepository.markCheckFailure(
            productId = product.id,
            checkedAt = now,
            errorCode = error.kind.name,
            errorMessage = error.message,
        )
        productRepository.logCheck(
            PriceCheckLog(
                productId = product.id,
                status = PriceCheckLog.STATUS_ERROR,
                kind = error.kind.name,
                source = "",
                message = error.message,
                variantsCount = 0,
                createdAt = now,
            ),
        )
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
            tag = current.variantKey,
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
            tag = TARGET_NOTIFICATION_TAG,
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
