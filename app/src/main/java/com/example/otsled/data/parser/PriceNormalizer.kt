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

    fun extractPricesFromOfferText(text: String): List<Double> {
        val cleaned = text
            .replace('\u00A0', ' ')
            .replace(articlePattern, " ")
            .replace(volumePattern, " ")

        return rubPricePattern.findAll(cleaned)
            .mapNotNull { match -> normalize("${match.groupValues[1]} руб") }
            .filter { it in MIN_PRICE..MAX_PRICE }
            .distinct()
            .take(2)
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
