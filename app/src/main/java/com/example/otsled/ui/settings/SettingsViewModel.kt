package com.example.otsled.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.data.update.AppUpdateFeed
import com.example.otsled.data.update.UpdateChecker
import com.example.otsled.di.AppContainer
import com.example.otsled.domain.model.PriceCheckLog
import com.example.otsled.service.PriceCheckForegroundService
import com.example.otsled.util.ApkInstaller
import com.example.otsled.util.AppBuildInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Этап ручной проверки обновления: UI показывает одну кнопку, которая означает всё остальное. */
enum class UpdateStage {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    NEEDS_PERMISSION,
    READY,
    FAILED,
}

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: AppUpdateFeed.ReleaseInfo? = null,
    val localVersion: String? = null,
    /** Доля скачивания; null — когда сервер не сообщил длину (тогда прогресс не врал бы о 0%). */
    val progress: Float? = null,
    val message: String? = null,
)

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

    private val updateChecker: UpdateChecker = container.updateChecker

    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    /**
     * Проверка и скачивание — два отдельных действия. Одно нажатие «обнови» выглядело бы
     * удобнее, но по мобильной сети это ~20 МБ трафика, а пользователь вправе решить сам.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            val context = container.applicationContext
            val local = AppBuildInfo.versionName(context)
            _update.update { it.copy(stage = UpdateStage.CHECKING, localVersion = local, message = null) }

            updateChecker.fetchLatestRelease()
                .onSuccess { info ->
                    val stage = if (AppUpdateFeed.isNewer(local, info.versionName)) {
                        UpdateStage.AVAILABLE
                    } else {
                        UpdateStage.UP_TO_DATE
                    }
                    _update.update { it.copy(stage = stage, release = info) }
                }
                .onFailure { error ->
                    _update.update {
                        it.copy(stage = UpdateStage.FAILED, message = updateChecker.describe(error))
                    }
                }
        }
    }

    fun downloadUpdate() {
        val release = _update.value.release ?: return
        viewModelScope.launch {
            _update.update { it.copy(stage = UpdateStage.DOWNLOADING, progress = null, message = null) }
            updateChecker.downloadApk(release) { fraction ->
                // Прогресс приходит из IO-потока: update() вместо чтения .value, чтобы не потерять
                // параллельную смену этапа.
                _update.update { state -> state.copy(progress = fraction) }
            }.onSuccess {
                val stage = if (ApkInstaller.needsInstallPermission(container.applicationContext)) {
                    UpdateStage.NEEDS_PERMISSION
                } else {
                    UpdateStage.READY
                }
                _update.update { it.copy(stage = stage, progress = 1f) }
            }.onFailure { error ->
                _update.update {
                    it.copy(stage = UpdateStage.FAILED, message = updateChecker.describe(error), progress = null)
                }
            }
        }
    }

    /** Открывает системный установщик на уже скачанный файл. */
    fun installDownloaded() {
        val context = container.applicationContext
        val apk = updateChecker.downloadedApk()
        if (apk == null) {
            _update.update {
                it.copy(
                    stage = UpdateStage.FAILED,
                    message = "файл установки исчез из кэша — скачай заново",
                )
            }
            return
        }
        runCatching { context.startActivity(ApkInstaller.installIntent(context, apk)) }
            .onFailure { error ->
                _update.update {
                    it.copy(stage = UpdateStage.FAILED, message = updateChecker.describe(error))
                }
            }
    }

    fun openInstallPermissionSettings() {
        val context = container.applicationContext
        runCatching { context.startActivity(ApkInstaller.permissionIntent(context)) }
    }

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
