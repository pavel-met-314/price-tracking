package com.example.otsled.domain.model

/**
 * Что приложение знает о товаре по результатам последних проверок.
 *
 * Разделение принципиальное: одни статусы про **товар** («его нет на сайте»), другие про **нас**
 * («сайт не отдал страницу»). Смешивать их нельзя: «проверка упала» не значит «товар сняли»,
 * а «товара нет» не лечится повторной проверкой. От этого же зависит, стоит ли вообще продолжать
 * гонять WebView по этой ссылке.
 */
enum class ProductStatus(
    /** true — сообщение касается товара, а не нашей способности его прочитать. */
    val concernsProduct: Boolean,
    /** true — товару нужен глаз да глаз: в списке это красный текст и фильтр «с проблемами». */
    val isProblem: Boolean,
) {
    /** Цены приходят, всё в порядке. */
    OK(concernsProduct = false, isProblem = false),

    /** Страница отвечает, но прайс-предложений нет: объёмы сняли либо всё распродано. */
    OUT_OF_STOCK(concernsProduct = true, isProblem = true),

    /** Страницы товара нет: чаще всего снят с продажи, реже — битая ссылка. */
    NOT_FOUND(concernsProduct = true, isProblem = true),

    /** Страница есть, но цены разобрать не удалось — вероятно, магазин сменил вёрстку. */
    PARSE_FAILED(concernsProduct = false, isProblem = true),

    /** Сайт запросил проверку браузера или сеть недоступна: это про нас, а не про товар. */
    ACCESS_FAILED(concernsProduct = false, isProblem = true),

    /** Проверку ещё ни разу не завершили успешно — цены просто нет. */
    NO_DATA(concernsProduct = false, isProblem = false),
}

/**
 * Статус товара. Архив сюда не входит: это решение пользователя, а не наблюдение за сайтом,
 * и смешать «в архиве» с «исчез с сайта» — самый короткий способ обмануть фильтрами.
 *
 * Категория последней ошибки первична: `consecutiveFailures` растёт и на сетевых сбоях, а вывод
 * «товара нет» должен опираться только на ответ самого сайта.
 */
val TrackedProduct.status: ProductStatus
    get() = when (lastErrorCode) {
        ParseResultKind.NOT_FOUND -> ProductStatus.NOT_FOUND
        ParseResultKind.OUT_OF_STOCK -> ProductStatus.OUT_OF_STOCK
        ParseResultKind.PARSE -> ProductStatus.PARSE_FAILED
        ParseResultKind.BOT_CHALLENGE, ParseResultKind.NETWORK -> ProductStatus.ACCESS_FAILED
        else -> if (lastPrice == null && lastCheckedAt == null) ProductStatus.NO_DATA else ProductStatus.OK
    }

/**
 * Пора ли самому прекращать проверки товара. Правило вынесено из `PriceCheckUseCase` нарочно:
 * оно единственное в приложении, которое молча отключает целую запись, и проверять его нужно
 * отдельно от сети и уведомлений.
 *
 * Только «страницы нет»: сетевой сбой и блокировка — наши сбои, а «нет в наличии» — как раз то,
 * за чем и следят (товар может вернуться).
 */
fun shouldAutoPauseOnNotFound(
    lastErrorKind: String?,
    failuresAfterThisCheck: Int,
    threshold: Int,
): Boolean = lastErrorKind == ParseResultKind.NOT_FOUND && failuresAfterThisCheck >= threshold

/** Проверки приостановлены пользователем или приложением (архив — не пауза: там решения разные). */
val TrackedProduct.isPaused: Boolean
    get() = !isActive && archivedAt == null

