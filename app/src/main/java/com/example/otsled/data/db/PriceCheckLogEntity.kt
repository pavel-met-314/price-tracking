package com.example.otsled.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Кольцевой журнал проверок. Нужен потому, что главный риск приложения — внешний сайт:
 * парсер может начать отдавать не те цены, и без журнала это нечем объяснить ни пользователю,
 * ни разработчику (adb не у каждого под рукой). Храним только последние записи, см. prune.
 */
@Entity(
    tableName = "price_check_log",
    indices = [Index("createdAt")],
)
data class PriceCheckLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** null — событие не привязано к конкретному товару (например, запуск пакета проверок). */
    val productId: Long?,
    /** "OK" либо "ERROR". */
    val status: String,
    /** Категория результата: ParseResult.Kind.name либо "" для успеха. */
    val kind: String = "",
    /** Откуда пришли цены: HTTP / WEBVIEW / UNKNOWN. */
    val source: String = "",
    val message: String? = null,
    val variantsCount: Int = 0,
    val createdAt: Long,
) {
    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_ERROR = "ERROR"

    }
}
