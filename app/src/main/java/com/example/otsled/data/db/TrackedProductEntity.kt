package com.example.otsled.data.db

import androidx.room.ColumnInfo
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
    /** Когда проверка последний раз реально получила цены — отличается от lastCheckedAt при ошибках. */
    val lastSuccessAt: Long? = null,
    /** Категория последней ошибки (ParseResult.Kind.name), пустая строка — всё в порядке. */
    @ColumnInfo(defaultValue = "''")
    val lastErrorCode: String = "",
    val lastErrorMessage: String? = null,
    /** Подряд идущие неудачи: по ним UI показывает предупреждение, а WorkManager ретраит. */
    @ColumnInfo(defaultValue = "0")
    val consecutiveFailures: Int = 0,
)
