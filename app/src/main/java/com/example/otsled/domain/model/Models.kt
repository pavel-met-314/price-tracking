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

data class PriceHistoryEntry(
    val id: Long = 0,
    val productId: Long,
    val price: Double,
    val checkedAt: Long,
)
