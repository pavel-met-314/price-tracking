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
    val lastSuccessAt: Long? = null,
    val lastErrorCode: String = "",
    val lastErrorMessage: String? = null,
    val consecutiveFailures: Int = 0,
) {
    /** Проверка запускалась, но цены получены не были — в списке это надо показать честно. */
    val hasCheckProblem: Boolean
        get() = consecutiveFailures > 0

    val isBotBlocked: Boolean
        get() = lastErrorCode == ParseResultKind.BOT_CHALLENGE
}

/** Категории ошибок парсинга; значения совпадают с ParseResult.Kind.name. */
object ParseResultKind {
    const val PARSE = "PARSE"
    const val BOT_CHALLENGE = "BOT_CHALLENGE"
    const val NETWORK = "NETWORK"
    const val NOT_FOUND = "NOT_FOUND"
    const val OUT_OF_STOCK = "OUT_OF_STOCK"
}

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
    val isTracked: Boolean = true,
    val lastSeenAt: Long? = null,
) {
    fun displayName(): String = buildString {
        append(volume)
        if (label == "уценка") {
            append(" (уценка)")
        }
    }

    fun priceLine(): String = "${displayName()} — ${formatPrice(lastPrice)} ₽"

    val volumeMl: Int?
        get() = VOLUME_DIGITS.find(volume)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Цена за миллилитр — то, по чему реально сравнивают 30/50/100 мл. Заодно служит
     * индикатором кривого парсинга: 2 ₽/мл у полноразмерного флакона быть не может.
     */
    fun pricePerMl(): Double? {
        val ml = volumeMl ?: return null
        if (ml <= 0) return null
        return lastPrice / ml
    }

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) price.toLong().toString() else price.toString()
    }

    private companion object {
        val VOLUME_DIGITS = Regex("(\\d+)")
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

/** Запись кольцевого журнала проверок — то, что видно в настройках при «непонятном» парсинге. */
data class PriceCheckLog(
    val id: Long = 0,
    val productId: Long?,
    val status: String,
    val kind: String,
    val source: String,
    val message: String?,
    val variantsCount: Int,
    val createdAt: Long,
) {
    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_ERROR = "ERROR"
    }
}
