package com.example.otsled.domain.model

import com.example.otsled.data.db.PriceHistoryEntryEntity
import com.example.otsled.data.db.TrackedProductEntity

fun TrackedProductEntity.toDomain(): TrackedProduct = TrackedProduct(
    id = id,
    url = url,
    title = title,
    targetPrice = targetPrice,
    lastPrice = lastPrice,
    lastCheckedAt = lastCheckedAt,
    isActive = isActive,
    notifyOnAnyChange = notifyOnAnyChange,
    notifyOnTargetReached = notifyOnTargetReached,
)

fun TrackedProduct.toEntity(): TrackedProductEntity = TrackedProductEntity(
    id = id,
    url = url,
    title = title,
    targetPrice = targetPrice,
    lastPrice = lastPrice,
    lastCheckedAt = lastCheckedAt,
    isActive = isActive,
    notifyOnAnyChange = notifyOnAnyChange,
    notifyOnTargetReached = notifyOnTargetReached,
)

fun PriceHistoryEntryEntity.toDomain(): PriceHistoryEntry = PriceHistoryEntry(
    id = id,
    productId = productId,
    price = price,
    checkedAt = checkedAt,
)

fun PriceHistoryEntry.toEntity(): PriceHistoryEntryEntity = PriceHistoryEntryEntity(
    id = id,
    productId = productId,
    price = price,
    checkedAt = checkedAt,
)
