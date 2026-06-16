package com.example.otsled

import android.app.Application
import com.example.otsled.di.AppContainer

class OtsledApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (container.settingsRepository.isForegroundServiceEnabled()) {
            com.example.otsled.service.PriceCheckForegroundService.start(this)
        } else {
            container.priceCheckScheduler().schedulePeriodicCheck()
        }
    }
}
