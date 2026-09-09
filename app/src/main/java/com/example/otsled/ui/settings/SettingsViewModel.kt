package com.example.otsled.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.model.PriceCheckLog
import com.example.otsled.service.PriceCheckForegroundService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val settingsRepository: SettingsRepository = container.settingsRepository

    val checkIntervalMinutes = settingsRepository.checkIntervalMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.getCheckIntervalMinutes())

    val foregroundServiceEnabled = settingsRepository.foregroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.isForegroundServiceEnabled())

    /** Последние проверки — то, чем объясняется «странно распарсилось», без adb и логов. */
    val checkLog = container.productRepository
        .observeLog(LOG_DISPLAY_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setCheckInterval(minutes: Int) {
        settingsRepository.setCheckIntervalMinutes(minutes)
        if (!settingsRepository.isForegroundServiceEnabled()) {
            container.priceCheckScheduler.schedulePeriodicCheck()
        }
    }

    fun setForegroundServiceEnabled(enabled: Boolean) {
        settingsRepository.setForegroundServiceEnabled(enabled)
        viewModelScope.launch {
            val context = container.applicationContext
            if (enabled) {
                container.priceCheckScheduler.cancelPeriodicCheck()
                PriceCheckForegroundService.start(context)
            } else {
                PriceCheckForegroundService.stop(context)
                container.priceCheckScheduler.schedulePeriodicCheck()
            }
        }
    }

    /**
     * Анти-бот «запомнил», что HTTP-путь бесполезен, и уходит сразу в WebView. Если пользователь
     * сам открыл сайт в браузере приложения или защита изменилась, сброс возвращает быстрый путь.
     */
    fun resetParseSession() {
        container.parseSessionStore.clear()
    }

    companion object {
        private const val LOG_DISPLAY_LIMIT = 25
    }
}
