package com.example.otsled.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "price_history",
    foreignKeys = [
        ForeignKey(
            entity = TrackedProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("productId")],
)
data class PriceHistoryEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productId: Long,
    val price: Double,
    val checkedAt: Long,
)
