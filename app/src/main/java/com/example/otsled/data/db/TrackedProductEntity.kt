package com.example.otsled.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class TrackedProductEntity(
    @PrimaryKey(autoGenerate = true)
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
