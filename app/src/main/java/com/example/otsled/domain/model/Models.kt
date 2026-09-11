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
    /** Когда товар убран в архив; null — отслеживается как обычно. */
    val archivedAt: Long? = null,
    /** Название, вписанное пользователем; пустое — показываем прочитанное со страницы. */
    val titleOverride: String? = null,
    /** Что перестало находиться на странице (см. domain/LayoutFingerprint); пустая строка — всё штатно. */
    val layoutNote: String = "",
    /** Снимок последней удачной разметки; null — сравнивать ещё не с чем. */
    val fingerprint: String? = null,
) {
    /** Как товар называть в UI и в уведомлениях: ручное название важнее разбора. */
    val displayName: String
        get() = titleOverride?.takeIf { it.isNotBlank() } ?: title

    /** Проверка запускалась, но цены получены не были — в списке это надо показать честно. */
    val hasCheckProblem: Boolean
        get() = consecutiveFailures > 0 || hasLayoutSuspicion

    /** Вёрстка поменялась: цены находятся не все. Это наша проблема, но решать её пользователю. */
    val hasLayoutSuspicion: Boolean
        get() = layoutNote.isNotBlank()

    val isBotBlocked: Boolean
        get() = lastErrorCode == ParseResultKind.BOT_CHALLENGE

    /** В архиве — значит не проверяется и в общем списке не участвует. */
    val isArchived: Boolean
        get() = archivedAt != null
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

        /** Не ошибка парсера, а решение приложения: отслеживание товара поставлено на паузу. */
        const val KIND_PAUSED = "PAUSED"

        /** Цены мы получили, но часть разметки перестала читаться — подозрение на смену вёрстки. */
        const val KIND_LAYOUT = "LAYOUT"
    }
}
