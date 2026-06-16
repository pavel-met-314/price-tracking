package com.example.otsled.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "product_variants",
    foreignKeys = [
        ForeignKey(
            entity = TrackedProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("productId"),
        Index(value = ["productId", "variantKey"], unique = true),
    ],
)
data class ProductVariantEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productId: Long,
    val variantKey: String,
    val volume: String,
    val label: String = "",
    val article: String? = null,
    val lastPrice: Double,
    val oldPrice: Double? = null,
    val lastCheckedAt: Long? = null,
)
