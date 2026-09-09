package com.example.otsled.data.parser

/**
 * Парсер ищет строки вида «объём + цена», но на странице товара много и других мест с такими
 * словами: блок доставки, начисленные бонусы, «цена за 1 мл», рекомендации «похожие ароматы».
 * Попадание такого блока в список вариантов даёт фантомный объём и несуществующую цену —
 * главный источник «иногда парсится некорректно».
 *
 * Отфильтрованные строки отбрасываются целиком, а не «поправляются»: лучше недосчитать вариант,
 * чем сохранить цену доставки как цену товара.
 */
object PriceNoise {

    /** Слова, после которых цена относится не к аромату, а к обслуживанию заказа. */
    private val NOISE_KEYWORDS = listOf(
        "доставк",
        "самовывоз",
        "оплат",
        "возврат",
        "бонус",
        "балл",
        "подарок",
        "промоко",
        "купон",
        "рассрочк",
        "отзыв",
        "похож",
        "рекоменду",
        "с этим товар",
        "аналог",
        "сертификат",
        "также смотрите",
        "вместе с этим",
    )

    /** «Цена за 1 мл: 213 руб.» — объём тут служебный, а не вариант товара. */
    private val PRICE_PER_ML_REGEX = Regex(
        "(за\\s*1\\s*мл|цена\\s*за\\s*мл|₽\\s*[/\\s]\\s*мл|руб\\s*[/\\s]\\s*мл|за\\s*миллилитр)",
        RegexOption.IGNORE_CASE,
    )

    fun isPricePerMl(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return PRICE_PER_ML_REGEX.containsMatchIn(text)
    }

    private val PER_ML_WITH_PRICE_REGEX = Regex(
        "за\\s*1?\\s*мл[^0-9]{0,12}\\d[\\d\\s\\u00A0]{0,10}\\s*(?:руб|₽)",
        RegexOption.IGNORE_CASE,
    )

    private val PER_ML_SUFFIX_REGEX = Regex(
        "\\d[\\d\\s.,\\u00A0]{0,10}\\s*(?:₽|руб\\.?)\\s*/\\s*мл",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Удаляет из строки фрагмент «цена за 1 мл». Если этого не сделать, в тексте вида
     * «50 мл · 4 200 руб · 84 ₽/мл» первым совпадением объёма станет «1 мл», и парсер
     * придумает несуществующий флакон по цене за миллилитр.
     */
    fun stripPricePerMl(text: String): String = text
        .replace(PER_ML_WITH_PRICE_REGEX, " ")
        .replace(PER_ML_SUFFIX_REGEX, " ")

    fun hasNoiseKeyword(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return NOISE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * true — строку нельзя использовать как ценовое предложение.
     *
     * Наличие артикула делает строку достоверным оффером (на сайте «Артикул» печатается только
     * внутри реального предложения), поэтому ключевые слова про доставку/бонусы в такой строке
     * игнорируются: иначе мы потеряли бы настоящую цену из-за случайного «баллон» в названии.
     * «Цена за 1 мл» отбрасывается всегда — там артикула нет по определению.
     */
    fun isNoiseOffer(text: String?, hasArticle: Boolean = false): Boolean {
        if (isPricePerMl(text)) return true
        if (hasArticle) return false
        return hasNoiseKeyword(text)
    }

    /**
     * Цена «принадлежит» служебной фразе, если непосредственно перед ней стоит слово про
     * доставку/бонусы. Нужна именно позиция, а не проверка строки целиком: в одной ячейке
     * может стоять настоящая цена аромата и цена доставки, и выбросить строку целиком —
     * потерять товар.
     */
    fun isPriceAttachedToNoise(text: String, priceOffset: Int, lookback: Int = 40): Boolean {
        val start = (priceOffset - lookback).coerceAtLeast(0)
        if (start >= priceOffset || start >= text.length) return false
        val before = text.substring(start, minOf(priceOffset, text.length)).lowercase()
        return NOISE_KEYWORDS.any { before.contains(it) }
    }

    /**
     * Служебные блоки вырезаются из документа до поиска предложений. Теги script/style/noscript
     * здесь намеренно НЕ перечислены: их удаление лишило бы нас JSON-LD разметки с ценой,
     * а Jsoup и так не отдаёт их содержимое в Element.text().
     */
    fun noiseBlockSelectors(): List<String> = listOf(
        "[class*=similar]",
        "[class*=recommend]",
        "[class*=related]",
        "[class*=other-product]",
        "[class*=chose-product]",
        "[id*=similar]",
        "[id*=recommend]",
        "[class*=review]",
        "[class*=comment]",
        "[class*=footer]",
        "[class*=subscribe]",
        "[class*=cookie]",
        "[class*=popup]",
    )
}
