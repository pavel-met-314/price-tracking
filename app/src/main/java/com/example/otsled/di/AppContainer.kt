package com.example.otsled.di

import android.content.Context
import com.example.otsled.data.db.OtsledDatabase
import com.example.otsled.data.repository.ProductRepository
import com.example.otsled.data.settings.SettingsRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: OtsledDatabase by lazy {
        OtsledDatabase.getInstance(appContext)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val productRepository: ProductRepository by lazy {
        ProductRepository(database.productDao())
    }

    private var priceParser: com.example.otsled.data.parser.AllureParfumPriceParser? = null
    private var notificationManager: com.example.otsled.notification.PriceNotificationManager? = null
    private var priceCheckScheduler: com.example.otsled.worker.PriceCheckScheduler? = null

    fun priceParser(): com.example.otsled.data.parser.AllureParfumPriceParser {
        return priceParser ?: com.example.otsled.data.parser.AllureParfumPriceParser().also {
            priceParser = it
        }
    }

    fun notificationManager(): com.example.otsled.notification.PriceNotificationManager {
        return notificationManager
            ?: com.example.otsled.notification.PriceNotificationManager(appContext).also {
                notificationManager = it
            }
    }

    fun priceCheckScheduler(): com.example.otsled.worker.PriceCheckScheduler {
        return priceCheckScheduler
            ?: com.example.otsled.worker.PriceCheckScheduler(appContext, settingsRepository).also {
                priceCheckScheduler = it
            }
    }
}
