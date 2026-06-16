package com.example.otsled.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.di.AppContainer
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

    fun setCheckInterval(minutes: Int) {
        settingsRepository.setCheckIntervalMinutes(minutes)
        if (!settingsRepository.isForegroundServiceEnabled()) {
            container.priceCheckScheduler().schedulePeriodicCheck()
        }
    }

    fun setForegroundServiceEnabled(enabled: Boolean) {
        settingsRepository.setForegroundServiceEnabled(enabled)
        viewModelScope.launch {
            val context = container.applicationContext
            if (enabled) {
                container.priceCheckScheduler().cancelPeriodicCheck()
                PriceCheckForegroundService.start(context)
            } else {
                PriceCheckForegroundService.stop(context)
                container.priceCheckScheduler().schedulePeriodicCheck()
            }
        }
    }
}
