package com.example.otsled.data.parser

object PriceNormalizer {
    private val pricePattern = Regex("""(\d[\d\s\u00A0]*(?:[.,]\d{1,2})?)""")

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

    private fun extractNumber(text: String): Double? {
        val match = pricePattern.find(text) ?: return null
        val numeric = match.groupValues[1]
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace(',', '.')
        return numeric.toDoubleOrNull()
    }
}
