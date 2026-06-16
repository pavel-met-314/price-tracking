package com.example.otsled.di

import android.content.Context
import com.example.otsled.data.db.OtsledDatabase
import com.example.otsled.data.parser.AllureParfumPriceParser
import com.example.otsled.data.parser.WebViewPriceFetcher
import com.example.otsled.data.repository.ProductRepository
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.domain.PriceCheckUseCase
import com.example.otsled.notification.PriceNotificationManager
import com.example.otsled.worker.PriceCheckScheduler

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext

    private val database: OtsledDatabase by lazy {
        OtsledDatabase.getInstance(applicationContext)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    val productRepository: ProductRepository by lazy {
        ProductRepository(database.productDao())
    }

    fun priceParser(): AllureParfumPriceParser = AllureParfumPriceParser()

    fun webViewPriceFetcher(): WebViewPriceFetcher = WebViewPriceFetcher(applicationContext)

    fun notificationManager(): PriceNotificationManager = PriceNotificationManager(applicationContext)

    fun priceCheckScheduler(): PriceCheckScheduler =
        PriceCheckScheduler(applicationContext, settingsRepository)

    fun priceCheckUseCase(): PriceCheckUseCase = PriceCheckUseCase(
        context = applicationContext,
        productRepository = productRepository,
        priceParser = priceParser(),
        notificationManager = notificationManager(),
        webViewPriceFetcher = webViewPriceFetcher(),
    )
}
