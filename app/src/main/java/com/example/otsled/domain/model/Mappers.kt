package com.example.otsled.domain.model

import com.example.otsled.data.db.PriceCheckLogEntity
import com.example.otsled.data.db.PriceHistoryEntryEntity
import com.example.otsled.data.db.ProductVariantEntity
import com.example.otsled.data.db.TrackedProductEntity
import com.example.otsled.data.parser.ParsedProductVariant

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
    lastSuccessAt = lastSuccessAt,
    lastErrorCode = lastErrorCode,
    lastErrorMessage = lastErrorMessage,
    consecutiveFailures = consecutiveFailures,
    archivedAt = archivedAt,
    titleOverride = titleOverride,
    layoutNote = lastLayoutNote,
    fingerprint = lastFingerprint,
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
    lastSuccessAt = lastSuccessAt,
    lastErrorCode = lastErrorCode,
    lastErrorMessage = lastErrorMessage,
    consecutiveFailures = consecutiveFailures,
    archivedAt = archivedAt,
    titleOverride = titleOverride,
    lastLayoutNote = layoutNote,
    lastFingerprint = fingerprint,
)

fun ProductVariantEntity.toDomain(): ProductVariant = ProductVariant(
    id = id,
    productId = productId,
    variantKey = variantKey,
    volume = volume,
    label = label,
    article = article,
    lastPrice = lastPrice,
    oldPrice = oldPrice,
    lastCheckedAt = lastCheckedAt,
    isTracked = isTracked,
    lastSeenAt = lastSeenAt,
)

fun ProductVariant.toEntity(): ProductVariantEntity = ProductVariantEntity(
    id = id,
    productId = productId,
    variantKey = variantKey,
    volume = volume,
    label = label,
    article = article,
    lastPrice = lastPrice,
    oldPrice = oldPrice,
    lastCheckedAt = lastCheckedAt,
    isTracked = isTracked,
    lastSeenAt = lastSeenAt,
)

/** Снятый с продажи вариант возвращается в отслеживание сам, если объём снова появился на странице. */
fun ParsedProductVariant.toEntity(productId: Long, checkedAt: Long?): ProductVariantEntity =
    ProductVariantEntity(
        productId = productId,
        variantKey = variantKey(),
        volume = volume,
        label = label,
        article = article,
        lastPrice = price,
        oldPrice = oldPrice,
        lastCheckedAt = checkedAt,
        isTracked = true,
        lastSeenAt = checkedAt,
    )

fun PriceHistoryEntryEntity.toDomain(): PriceHistoryEntry = PriceHistoryEntry(
    id = id,
    productId = productId,
    variantId = variantId,
    volumeLabel = volumeLabel,
    price = price,
    checkedAt = checkedAt,
)

fun PriceHistoryEntry.toEntity(): PriceHistoryEntryEntity = PriceHistoryEntryEntity(
    id = id,
    productId = productId,
    variantId = variantId,
    volumeLabel = volumeLabel,
    price = price,
    checkedAt = checkedAt,
)

fun PriceCheckLogEntity.toDomain(): PriceCheckLog = PriceCheckLog(
    id = id,
    productId = productId,
    status = status,
    kind = kind,
    source = source,
    message = message,
    variantsCount = variantsCount,
    createdAt = createdAt,
)

fun PriceCheckLog.toEntity(): PriceCheckLogEntity = PriceCheckLogEntity(
    id = id,
    productId = productId,
    status = status,
    kind = kind,
    source = source,
    message = message,
    variantsCount = variantsCount,
    createdAt = createdAt,
)

/**
 * Строка резервной копии -> запись базы. Цену и отметки сохраняем как есть: копия нужна в том
 * числе ради истории, которую самим не восстановить.
 */
fun com.example.otsled.domain.BackupProductRow.toEntity(id: Long): TrackedProductEntity = TrackedProductEntity(
    id = id,
    url = url,
    title = title,
    targetPrice = targetPrice,
    lastPrice = lastPrice,
    lastCheckedAt = lastCheckedAt,
    isActive = isActive,
    notifyOnAnyChange = notifyOnAnyChange,
    notifyOnTargetReached = notifyOnTargetReached,
    lastSuccessAt = lastSuccessAt,
    archivedAt = archivedAt,
    titleOverride = titleOverride,
)
