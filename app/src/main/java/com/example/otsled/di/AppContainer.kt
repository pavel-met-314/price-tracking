package com.example.otsled.di

import android.content.Context
import com.example.otsled.data.db.OtsledDatabase
import com.example.otsled.data.parser.AllureParfumPriceParser
import com.example.otsled.data.parser.PricePageLoader
import com.example.otsled.data.parser.WebViewPriceFetcher
import com.example.otsled.data.repository.ProductRepository
import com.example.otsled.data.site.AllureSiteSearch
import com.example.otsled.data.settings.ParseSessionStore
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.data.update.UpdateChecker
import com.example.otsled.domain.PriceCheckUseCase
import com.example.otsled.notification.PriceNotificationManager
import com.example.otsled.worker.PriceCheckScheduler

/**
 * Ручной DI без Hilt: объектов мало, а порядок инициализации важен.
 *
 * Всё, что держит сетевые ресурсы (OkHttpClient со своим connection pool и пулом потоков),
 * снимается ленивыми singletons — иначе на каждом цикле проверки создавался бы новый клиент,
 * и фоновая служба медленно текла бы потоками.
 */
class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext

    private val database: OtsledDatabase by lazy {
        OtsledDatabase.getInstance(applicationContext)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    val parseSessionStore: ParseSessionStore by lazy {
        ParseSessionStore(applicationContext)
    }

    val productRepository: ProductRepository by lazy {
        ProductRepository(database.productDao())
    }

    private val priceParser: AllureParfumPriceParser by lazy {
        // Контекст нужен, чтобы OkHttp брал куки из хранилища WebView.
        AllureParfumPriceParser(applicationContext)
    }

    private val webViewPriceFetcher: WebViewPriceFetcher by lazy {
        WebViewPriceFetcher(applicationContext)
    }

    val pricePageLoader: PricePageLoader by lazy {
        PricePageLoader(
            parser = priceParser,
            webViewFetcher = webViewPriceFetcher,
            sessionStore = parseSessionStore,
        )
    }

    val allureSiteSearch: AllureSiteSearch by lazy {
        AllureSiteSearch(
            context = applicationContext,
            webViewFetcher = webViewPriceFetcher,
            sessionStore = parseSessionStore,
        )
    }

    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(applicationContext)
    }

    val notificationManager: PriceNotificationManager by lazy {
        PriceNotificationManager(applicationContext)
    }

    val priceCheckScheduler: PriceCheckScheduler by lazy {
        PriceCheckScheduler(applicationContext, settingsRepository)
    }

    val priceCheckUseCase: PriceCheckUseCase by lazy {
        PriceCheckUseCase(
            context = applicationContext,
            productRepository = productRepository,
            pricePageLoader = pricePageLoader,
            notificationManager = notificationManager,
        )
    }
}
