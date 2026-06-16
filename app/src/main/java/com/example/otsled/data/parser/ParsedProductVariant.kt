package com.example.otsled.data.parser

data class ParsedProductVariant(
    val volume: String,
    val label: String = "",
    val article: String? = null,
    val price: Double,
    val oldPrice: Double? = null,
) {
    fun variantKey(): String = article?.takeIf { it.isNotBlank() } ?: buildString {
        append(volume)
        if (label.isNotBlank()) {
            append('|')
            append(label)
        }
    }

    fun displayName(): String = buildString {
        append(volume)
        if (label == "уценка") {
            append(" (уценка)")
        }
    }

    fun priceLine(): String = "${displayName()} — ${formatPrice(price)} ₽"

    private fun formatPrice(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
