package com.example.otsled.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.otsled.domain.AlertPolicy
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

    private val _minNotifyChangePercent = MutableStateFlow(getMinNotifyChangePercent())
    val minNotifyChangePercent: StateFlow<Int> = _minNotifyChangePercent.asStateFlow()

    private val _quietHoursEnabled = MutableStateFlow(isQuietHoursEnabled())
    val quietHoursEnabled: StateFlow<Boolean> = _quietHoursEnabled.asStateFlow()

    private val _quietWindow = MutableStateFlow(getQuietStartMinute() to getQuietEndMinute())
    val quietWindow: StateFlow<Pair<Int, Int>> = _quietWindow.asStateFlow()

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

    /** Порог «сообщать об изменении цены не меньше чем на N %»: 0 — сообщать о любом. */
    fun getMinNotifyChangePercent(): Int = prefs.getInt(KEY_MIN_NOTIFY_PERCENT, DEFAULT_MIN_NOTIFY_PERCENT)

    fun setMinNotifyChangePercent(percent: Int) {
        prefs.edit().putInt(KEY_MIN_NOTIFY_PERCENT, percent.coerceIn(0, 50)).apply()
        _minNotifyChangePercent.value = percent.coerceIn(0, 50)
    }

    fun isQuietHoursEnabled(): Boolean = prefs.getBoolean(KEY_QUIET_HOURS, false)

    fun setQuietHoursEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUIET_HOURS, enabled).apply()
        _quietHoursEnabled.value = enabled
    }

    /** Границы тихого окна в минутах от полуночи; окно может идти через полночь. */
    fun getQuietStartMinute(): Int = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)

    fun getQuietEndMinute(): Int = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END)

    fun setQuietWindow(startMinute: Int, endMinute: Int) {
        prefs.edit()
            .putInt(KEY_QUIET_START, startMinute.mod(MINUTES_PER_DAY))
            .putInt(KEY_QUIET_END, endMinute.mod(MINUTES_PER_DAY))
            .apply()
        _quietWindow.value = startMinute.mod(MINUTES_PER_DAY) to endMinute.mod(MINUTES_PER_DAY)
    }

    /** Снимок для доменной логики: `PriceCheckUseCase` не должен знать про SharedPreferences. */
    fun alertPolicy(): AlertPolicy = AlertPolicy(
        minChangePercent = getMinNotifyChangePercent(),
        quietHoursEnabled = isQuietHoursEnabled(),
        quietStartMinute = getQuietStartMinute(),
        quietEndMinute = getQuietEndMinute(),
    )

    companion object {
        const val PREFS_NAME = "otsled_settings"
        const val KEY_CHECK_INTERVAL = "check_interval_minutes"
        const val KEY_FOREGROUND_SERVICE = "foreground_service_enabled"
        const val KEY_MIN_NOTIFY_PERCENT = "min_notify_change_percent"
        const val KEY_QUIET_HOURS = "quiet_hours_enabled"
        const val KEY_QUIET_START = "quiet_start_minute"
        const val KEY_QUIET_END = "quiet_end_minute"
        const val DEFAULT_INTERVAL_MINUTES = 15
        const val DEFAULT_MIN_NOTIFY_PERCENT = 0
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 7 * 60
        const val MINUTES_PER_DAY = 24 * 60
        val INTERVAL_OPTIONS = listOf(15, 30, 60)

        /** Варианты порога: «0» оставляем явным, чтобы «сообщать о любом» можно было вернуть. */
        val NOTIFY_PERCENT_OPTIONS = listOf(0, 1, 3, 5, 10)
    }
}
