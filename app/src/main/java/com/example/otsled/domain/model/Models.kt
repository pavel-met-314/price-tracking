package com.example.otsled.domain.model

data class TrackedProduct(
    val id: Long = 0,
    val url: String,
    val title: String,
    val targetPrice: Double?,
    val lastPrice: Double?,
    val lastCheckedAt: Long?,
    val isActive: Boolean = true,
    val notifyOnAnyChange: Boolean = true,
    val notifyOnTargetReached: Boolean = true,
)

data class ProductVariant(
    val id: Long = 0,
    val productId: Long,
    val variantKey: String,
    val volume: String,
    val label: String = "",
    val article: String? = null,
    val lastPrice: Double,
    val oldPrice: Double? = null,
    val lastCheckedAt: Long? = null,
) {
    fun displayName(): String = buildString {
        append(volume)
        if (label == "уценка") {
            append(" (уценка)")
        }
    }

    fun priceLine(): String = "${displayName()} — ${formatPrice(lastPrice)} ₽"

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) price.toLong().toString() else price.toString()
    }
}

data class PriceHistoryEntry(
    val id: Long = 0,
    val productId: Long,
    val variantId: Long? = null,
    val volumeLabel: String? = null,
    val price: Double,
    val checkedAt: Long,
)
