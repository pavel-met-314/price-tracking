package com.example.otsled.data.parser

object PriceNormalizer {
    private val pricePattern = Regex("""(\d[\d\s\u00A0]*(?:[.,]\d{1,2})?)""")
    private val articlePattern = Regex("""Артикул\s*\d+""", RegexOption.IGNORE_CASE)
    private val volumePattern = Regex("""\d+\s*мл\.?""", RegexOption.IGNORE_CASE)
    private val rubPricePattern = Regex("""(\d[\d\s\u00A0]{0,10})\s*руб\.?""", RegexOption.IGNORE_CASE)

    private const val MIN_PRICE = 1.0
    private const val MAX_PRICE = 500_000.0

    fun normalize(raw: String): Double? {
        val cleaned = raw
            .replace('\u00A0', ' ')
            .replace("руб.", "")
            .replace("руб", "")
            .replace("₽", "")
            .trim()

        if (cleaned.contains('-')) {
            val parts = cleaned.split('-').map { it.trim() }
            return parts.firstNotNullOfOrNull { extractNumber(it) }
        }

        return extractNumber(cleaned)
    }

    fun extractPricesFromOfferText(text: String): List<Double> =
        extractOfferPrices(text)
            .map { it.value }
            .distinct()
            .take(2)
            .toList()

    /**
     * Все «NNN руб» в тексте строки вместе с позициями. Позиции нужны, чтобы отличить цену
     * товара от цены доставки в той же строке: «50 мл · 4 200 руб · доставка 300 руб».
     * Артикул и объём вырезаются символами пробелов (а не удалением), чтобы позиции не съехали.
     */
    fun extractOfferPrices(text: String): List<PriceOccurrence> {
        val stripped = text
            .replace('\u00A0', ' ')
            .replace(articlePattern) { match -> " ".repeat(match.value.length) }
            .replace(volumePattern) { match -> " ".repeat(match.value.length) }

        return rubPricePattern.findAll(stripped)
            .mapNotNull { match ->
                val value = normalize("${match.groupValues[1]} руб") ?: return@mapNotNull null
                if (value !in MIN_PRICE..MAX_PRICE) return@mapNotNull null
                PriceOccurrence(offset = match.range.first, value = value)
            }
            .toList()
    }

    fun isReasonablePrice(price: Double): Boolean = price in MIN_PRICE..MAX_PRICE

    private fun extractNumber(text: String): Double? {
        val match = pricePattern.find(text) ?: return null
        val numeric = match.groupValues[1]
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace(',', '.')
        return numeric.toDoubleOrNull()
    }
}

/** Цена в тексте строки оффера: значение и где оно начинается. */
data class PriceOccurrence(
    val offset: Int,
    val value: Double,
)
