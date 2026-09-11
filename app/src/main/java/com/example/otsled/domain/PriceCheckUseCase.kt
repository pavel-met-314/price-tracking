package com.example.otsled.domain

import android.content.Context
import com.example.otsled.R
import com.example.otsled.data.parser.ParseResult
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.data.parser.PricePageLoader
import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.data.repository.ProductRepository
import com.example.otsled.domain.model.PriceCheckLog
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.ProductVariant
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.domain.model.shouldAutoPauseOnNotFound
import com.example.otsled.notification.PriceNotificationManager
import java.util.Calendar
import kotlinx.coroutines.delay

/** Итог прогона по всем товарам — нужен воркеру, чтобы решить, ретраить ли пакет. */
data class CheckOutcome(
    val total: Int,
    val succeeded: Int,
    val retryableFailures: Int,
    /** Товары, до которых не дошла очередь: цикл упёрся во временной бюджет. */
    val skippedByBudget: Int = 0,
) {
    val failed: Int get() = total - succeeded - skippedByBudget

    /**
     * Повтор имеет смысл, только если не удалось ничего: частичный успех означает, что сайт
     * отвечает, а отдельный товар сломался по своей причине (снят с продажи, удалена страница).
     * Пропущенные бюджетом товары тоже требуют повтора — иначе они будут ждать следующего
     * часового интервала без всякой причины.
     */
    val shouldRetry: Boolean
        get() = total > 0 && succeeded == 0 && (retryableFailures > 0 || skippedByBudget > 0)
}

class PriceCheckUseCase(
    private val context: Context,
    private val productRepository: ProductRepository,
    private val pricePageLoader: PricePageLoader,
    private val notificationManager: PriceNotificationManager,
    /**
     * Порог уведомлений и тихие часы читаются из настроек каждым решением, а не берутся из
     * SharedPreferences напрямую: домену достаточно знать правила, а не где они лежат.
     */
    private val alertPolicy: () -> AlertPolicy = { AlertPolicy.DEFAULT },
) {
    private companion object {
        /** Отдельный тег, чтобы «цель достигнута» не затиралась уведомлением об изменении цены. */
        const val TARGET_NOTIFICATION_TAG = "target-price"

        /** Свой тег: «приостановили» не должно затирать уведомление о цене и наоборот. */
        const val PAUSE_NOTIFICATION_TAG = "paused"

        /** Сколько разрешено проверять цены в одном фоновом цикле. */
        const val MAX_CYCLE_MILLIS = 6 * 60_000L

        /**
         * После скольких подряд «страница не найдена» отслеживание ставится на паузу. Три — чтобы
         * разовый 404 во время выгрузки каталога не убивал товар, и чтобы не гонять WebView
         * по заведомо мёртвой ссылке каждый час.
         */
        const val AUTO_PAUSE_AFTER_NOT_FOUND = 3
    }
    suspend fun checkProduct(product: TrackedProduct): ParseResult {
        val normalizedUrl = ProductUrlNormalizer.normalize(product.url) ?: product.url
        val result = pricePageLoader.fetchAndParse(normalizedUrl)
        val now = System.currentTimeMillis()

        when (result) {
            is ParseResult.Success -> handleSuccessfulCheck(product, result, now)
            is ParseResult.Error -> handleFailedCheck(product, result, now)
        }

        return result
    }

    /**
     * Прогон по всем товарам с временным бюджетом. WebView-путь может занимать десятки секунд
     * на товар, и без ограничения один цикл фонового обновления растягивается на полчаса
     * с включённым экраном и сетью — при большом списке это заметно по батарее.
     */
    suspend fun checkAllActiveProducts(): CheckOutcome {
        val products = productRepository.getActiveProducts()
        val deadline = System.currentTimeMillis() + MAX_CYCLE_MILLIS
        var succeeded = 0
        var retryable = 0
        var skipped = 0
        var budgetExceeded = false

        products.forEach { product ->
            if (budgetExceeded) {
                skipped++
                return@forEach
            }
            if (System.currentTimeMillis() > deadline) {
                budgetExceeded = true
                skipped++
                return@forEach
            }
            when (val result = checkProduct(product)) {
                is ParseResult.Success -> succeeded++
                is ParseResult.Error -> if (result.isRetryable) retryable++
            }
        }

        return CheckOutcome(
            total = products.size,
            succeeded = succeeded,
            retryableFailures = retryable,
            skippedByBudget = skipped,
        )
    }

    /**
     * Ручная «обновить всё»: те же проверки, что и в фоновом цикле, но с паузами между товарами и
     * прогрессом на экране. Пауза между запросами — не вежливость ради вежливости: залп из десяти
     * ссылок подряд анти-бот превращает в сорванную проверку, и человек получает «цену не достали»
     * там, где всё могло бы найтись.
     *
     * Бюджет у прогона свой, и остаток списка не считается ошибкой: его догоняет плановая проверка.
     */
    suspend fun checkAllOnce(): CheckOutcome {
        val products = productRepository.getActiveProducts()
        if (!ManualCheckState.begin(products.size)) {
            // Уже идёт прогон: повторное нажатие не должно начинать второй.
            return CheckOutcome(total = products.size, succeeded = 0, retryableFailures = 0)
        }

        val startedAt = System.currentTimeMillis()
        var succeeded = 0
        var retryable = 0
        var skipped = 0
        var spent = 0L

        try {
            products.forEachIndexed { index, product ->
                val plan = ManualCheckPacing.plan(
                    total = products.size,
                    alreadyDone = index,
                    elapsedMillis = spent,
                    avgCheckMillis = if (index == 0) null else spent / index,
                )
                if (plan.fits <= 0) {
                    skipped = products.size - index
                    ManualCheckState.advance(ManualCheckProgress(done = index, total = products.size, deferred = skipped))
                    return@forEach
                }

                val checkStartedAt = System.currentTimeMillis()
                when (val result = checkProduct(product)) {
                    is ParseResult.Success -> succeeded++
                    is ParseResult.Error -> if (result.isRetryable) retryable++
                }
                spent += System.currentTimeMillis() - checkStartedAt

                if (index < products.lastIndex) {
                    delay(ManualCheckPacing.DELAY_BETWEEN_CHECKS_MILLIS)
                    spent += ManualCheckPacing.DELAY_BETWEEN_CHECKS_MILLIS
                }
                ManualCheckState.advance(
                    ManualCheckProgress(
                        done = index + 1,
                        total = products.size,
                        deferred = (products.size - index - 1).coerceAtLeast(skipped),
                    ),
                )
            }
        } finally {
            ManualCheckState.finish()
        }

        return CheckOutcome(
            total = products.size,
            succeeded = succeeded,
            retryableFailures = retryable,
            skippedByBudget = skipped,
        )
    }

    private suspend fun handleSuccessfulCheck(
        product: TrackedProduct,
        result: ParseResult.Success,
        now: Long,
    ) {
        val newTitle = result.title.ifBlank { product.title }
        // Уведомления подписываем так, как товар называется у человека, а не так, как его назвал
        // магазин: ручное название задано именно затем, чтобы узнавать свой флакон.
        val displayTitle = product.titleOverride?.takeIf { it.isNotBlank() } ?: newTitle
        val parsedVariants = result.variants
        val previousVariants = productRepository.getVariants(product.id).associateBy { it.variantKey }
        val savedVariants = productRepository.replaceVariants(product.id, parsedVariants, now)
        val previousMinPrice = product.lastPrice
        val newMinPrice = parsedVariants.minOf { it.price }

        // Снимок берётся по тому, что нашлось на странице, а не по сохранённому: «мы сохранили три
        // объёма» и «магазин показал три объёма» — разные утверждения.
        noticeLayoutChanges(product, LayoutFingerprint.of(title = newTitle, variants = result.variants))

        savedVariants.forEach { variant ->
            val previous = previousVariants[variant.variantKey]
            if (previous == null || previous.lastPrice != variant.lastPrice) {
                productRepository.insertHistory(
                    PriceHistoryEntry(
                        productId = product.id,
                        variantId = variant.id,
                        volumeLabel = variant.displayName(),
                        price = variant.lastPrice,
                        checkedAt = now,
                    ),
                )
            }

            maybeNotifyVariantChange(product, displayTitle, previous, variant)
        }

        productRepository.markCheckSuccess(
            productId = product.id,
            title = newTitle,
            lastPrice = newMinPrice,
            checkedAt = now,
        )

        maybeNotifyTargetReached(product, displayTitle, previousMinPrice, newMinPrice, parsedVariants)

        productRepository.logCheck(
            PriceCheckLog(
                productId = product.id,
                status = PriceCheckLog.STATUS_OK,
                kind = "",
                source = result.source.name,
                message = null,
                variantsCount = parsedVariants.size,
                createdAt = now,
            ),
        )
    }

    /**
     * Сверяет снимок разметки с прошлым удачным. Если что-то перестало находиться — пишем
     * подозрение на товар (его видно в списке и в карточке) и разбираем его в журнале цифрами:
     * «было 5 объёмов, стало 0» полезно и пользователю, и мне при удалённой отладке.
     *
     * Важно, что проверка при этом остаётся успешной: цены мы получили, а вот получили ли мы всё —
     * другой вопрос, и врать «страница не читается» здесь значит увести диагностику в сторону.
     */
    private suspend fun noticeLayoutChanges(product: TrackedProduct, current: LayoutFingerprint) {
        val previous = LayoutFingerprint.parse(product.fingerprint)
        val found = LayoutChangeDetection.regressions(previous, current)

        productRepository.setLayoutInfo(
            productId = product.id,
            fingerprint = current.encode(),
            note = LayoutChangeDetection.noteKey(found),
        )

        if (found.isNotEmpty() && previous != null) {
            productRepository.logCheck(
                PriceCheckLog(
                    productId = product.id,
                    status = PriceCheckLog.STATUS_OK,
                    kind = PriceCheckLog.KIND_LAYOUT,
                    source = "",
                    message = found.joinToString("; ") { regression ->
                        context.getString(
                            regression.messageRes(),
                            countBefore(previous, regression),
                            countAfter(current, regression),
                        )
                    },
                    variantsCount = current.variants,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun countBefore(previous: LayoutFingerprint, regression: LayoutRegression): Int = when (regression) {
        LayoutRegression.VOLUMES_GONE -> previous.withVolume
        LayoutRegression.OLD_PRICES_GONE -> previous.withOldPrice
        LayoutRegression.ARTICLES_GONE -> previous.withArticle
        LayoutRegression.TITLE_GONE -> 1
    }

    private fun countAfter(current: LayoutFingerprint, regression: LayoutRegression): Int = when (regression) {
        LayoutRegression.VOLUMES_GONE -> current.withVolume
        LayoutRegression.OLD_PRICES_GONE -> current.withOldPrice
        LayoutRegression.ARTICLES_GONE -> current.withArticle
        LayoutRegression.TITLE_GONE -> 0
    }

    /** Минута суток по локальному времени устройства: тихие часы привязаны к часы человека, не к UTC. */
    private fun minuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return NotificationPolicy.minutesOfDay(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
    }

    private suspend fun handleFailedCheck(
        product: TrackedProduct,
        error: ParseResult.Error,
        now: Long,
    ) {
        productRepository.markCheckFailure(
            productId = product.id,
            checkedAt = now,
            errorCode = error.kind.name,
            errorMessage = error.message,
        )
        pauseIfPageIsGone(product, error, now)
        productRepository.logCheck(
            PriceCheckLog(
                productId = product.id,
                status = PriceCheckLog.STATUS_ERROR,
                kind = error.kind.name,
                source = "",
                message = error.message,
                variantsCount = 0,
                createdAt = now,
            ),
        )
    }

    /**
     * Страницы товара больше нет — повторные проверки такой ссылки только жгут WebView (десятки
     * секунд на товар) и журнал. Поэтому после [AUTO_PAUSE_AFTER_NOT_FOUND] подряд «не найдено»
     * отслеживание становится на паузу, а не молча продолжается вечно: решение (удалить, вернуть,
     * поправить ссылку) за пользователем.
     *
     * Сетевые сбои, блокировка и «не распарсилось» сюда не попадают сознательно: это наши проблемы,
     * а не судьба товара, и ставить из-за них товар «в снятые» значило бы врать.
     */
    private suspend fun pauseIfPageIsGone(product: TrackedProduct, error: ParseResult.Error, now: Long) {
        // Счётчик в базе уже увеличен этим прогоном, а в [product] лежит значение до него.
        val failures = product.consecutiveFailures + 1
        if (!shouldAutoPauseOnNotFound(error.kind.name, failures, AUTO_PAUSE_AFTER_NOT_FOUND)) return

        productRepository.setActive(product.id, active = false)
        val reason = context.getString(R.string.product_paused_message, displayTitleOf(product), failures)
        productRepository.logCheck(
            PriceCheckLog(
                productId = product.id,
                status = PriceCheckLog.STATUS_ERROR,
                kind = PriceCheckLog.KIND_PAUSED,
                source = "",
                message = reason,
                variantsCount = 0,
                createdAt = now,
            ),
        )
        notificationManager.showPriceAlert(
            productId = product.id,
            title = context.getString(R.string.product_paused_title),
            message = reason,
            tag = PAUSE_NOTIFICATION_TAG,
            // «Мы сами поставили товар на паузу» — это важно, но будить из-за этого человека
            // посреди ночи не за чем: в тихие часы кладём в теневой канал.
            silent = NotificationPolicy.deliveryForImportantNews(minuteOfDay(), alertPolicy()) == AlertDelivery.SILENT,
        )
    }

    /**
     * Порог в процентах отсекает уведомление целиком, но не запись в истории: «подешевело на 0,5 %»
     * человек всё равно увидит в списке и на графике — молчание телефона не должно означать
     * «приложение пропустило изменение».
     */
    private fun maybeNotifyVariantChange(
        product: TrackedProduct,
        title: String,
        previous: ProductVariant?,
        current: ProductVariant,
    ) {
        if (!product.notifyOnAnyChange) return
        if (previous == null || previous.lastPrice == current.lastPrice) return

        val percent = if (previous.lastPrice > 0.0) {
            (current.lastPrice - previous.lastPrice) / previous.lastPrice * 100.0
        } else {
            null
        }
        val delivery = NotificationPolicy.deliveryForPriceChange(percent, minuteOfDay(), alertPolicy())
        if (delivery == AlertDelivery.NONE) return

        notificationManager.showPriceAlert(
            productId = product.id,
            title = context.getString(R.string.price_changed_title),
            message = context.getString(
                R.string.price_changed_variant_message,
                title,
                current.displayName(),
                formatPrice(previous.lastPrice),
                formatPrice(current.lastPrice),
            ),
            tag = current.variantKey,
            silent = delivery == AlertDelivery.SILENT,
        )
    }

    private fun maybeNotifyTargetReached(
        product: TrackedProduct,
        title: String,
        previousMinPrice: Double?,
        newMinPrice: Double,
        variants: List<ParsedProductVariant>,
    ) {
        val targetPrice = product.targetPrice ?: return
        if (!product.notifyOnTargetReached) return

        val reachedVariant = variants
            .filter { it.price <= targetPrice }
            .minByOrNull { it.price }
            ?: return

        val wasAlreadyReached = previousMinPrice != null && previousMinPrice <= targetPrice
        if (wasAlreadyReached) return

        notificationManager.showPriceAlert(
            productId = product.id,
            title = context.getString(R.string.target_reached_title),
            message = context.getString(
                R.string.target_reached_variant_message,
                title,
                reachedVariant.displayName(),
                formatPrice(reachedVariant.price),
                formatPrice(targetPrice),
            ),
            tag = TARGET_NOTIFICATION_TAG,
            silent = NotificationPolicy.deliveryForImportantNews(minuteOfDay(), alertPolicy()) == AlertDelivery.SILENT,
        )
    }

    private fun displayTitleOf(product: TrackedProduct): String = product.displayName.ifBlank { product.url }

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) {
            price.toLong().toString()
        } else {
            price.toString()
        }
    }
}
