package com.example.otsled.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _checkIntervalMinutes = MutableStateFlow(getCheckIntervalMinutes())
    val checkIntervalMinutes: StateFlow<Int> = _checkIntervalMinutes.asStateFlow()

    private val _foregroundServiceEnabled = MutableStateFlow(isForegroundServiceEnabled())
    val foregroundServiceEnabled: StateFlow<Boolean> = _foregroundServiceEnabled.asStateFlow()

    fun getCheckIntervalMinutes(): Int =
        prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_INTERVAL_MINUTES)

    fun setCheckIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_CHECK_INTERVAL, minutes).apply()
        _checkIntervalMinutes.value = minutes
    }

    fun isForegroundServiceEnabled(): Boolean =
        prefs.getBoolean(KEY_FOREGROUND_SERVICE, false)

    fun setForegroundServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOREGROUND_SERVICE, enabled).apply()
        _foregroundServiceEnabled.value = enabled
    }

    companion object {
        const val PREFS_NAME = "otsled_settings"
        const val KEY_CHECK_INTERVAL = "check_interval_minutes"
        const val KEY_FOREGROUND_SERVICE = "foreground_service_enabled"
        const val DEFAULT_INTERVAL_MINUTES = 15
        val INTERVAL_OPTIONS = listOf(15, 30, 60)
    }
}
