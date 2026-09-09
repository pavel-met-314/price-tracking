package com.example.otsled.data.parser

/**
 * Результат проверки цены. Класс ошибок важен не для UI, а для фонового планировщика:
 * «сайт попросил проверить браузер» нужно повторить позже, а «товар не найден» — нет.
 */
sealed class ParseResult {

    data class Success(
        val title: String,
        val variants: List<ParsedProductVariant>,
        val source: PriceSource = PriceSource.UNKNOWN,
    ) : ParseResult() {
        val price: Double get() = variants.minOf { it.price }
    }

    data class Error(
        val message: String,
        val kind: Kind = Kind.PARSE,
    ) : ParseResult()

    enum class Kind {
        /** Разметку не удалось разобрать — вероятно, сайт изменился. */
        PARSE,

        /** Сработала анти-бот проверка браузера, нужен WebView и повтор. */
        BOT_CHALLENGE,

        /** Сеть недоступна/таймаут — имеет смысл ретрай с backoff. */
        NETWORK,

        /** Страницы нет: товар снят с продажи или ссылка неверна. */
        NOT_FOUND,

        /** Товар есть, но прайс-предложений не найдено (например, «нет в наличии»). */
        OUT_OF_STOCK,
    }

    /** true — ошибку стоит повторить позже, а не считать её окончательной. */
    val isRetryable: Boolean
        get() = this is Error && (kind == Kind.NETWORK || kind == Kind.BOT_CHALLENGE)
}
