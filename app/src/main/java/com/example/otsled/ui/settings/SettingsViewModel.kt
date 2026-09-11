package com.example.otsled.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.data.update.AppUpdateFeed
import com.example.otsled.domain.Backup
import com.example.otsled.domain.BackupDecodeResult
import com.example.otsled.domain.BackupFailure
import com.example.otsled.domain.BackupFormat
import com.example.otsled.domain.BackupPlan
import com.example.otsled.data.update.UpdateChecker
import com.example.otsled.di.AppContainer
import com.example.otsled.R
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** false у магазинной сборки: обновлять себя ей не положено, проверка остаётся справочной. */
    val canInstall: Boolean = true,
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

    private val _update = MutableStateFlow(
        UpdateUiState(canInstall = container.updateChecker.canInstallUpdates),
    )
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

    // ---------- порог уведомлений и тихие часы ----------

    val minNotifyPercent = settingsRepository.minNotifyChangePercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.getMinNotifyChangePercent())

    val quietHoursEnabled = settingsRepository.quietHoursEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.isQuietHoursEnabled())

    val quietWindow = settingsRepository.quietWindow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.getQuietStartMinute() to settingsRepository.getQuietEndMinute())

    fun setMinNotifyPercent(percent: Int) {
        settingsRepository.setMinNotifyChangePercent(percent)
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        settingsRepository.setQuietHoursEnabled(enabled)
    }

    fun setQuietWindow(startMinute: Int, endMinute: Int) {
        settingsRepository.setQuietWindow(startMinute, endMinute)
    }

    // ---------- резервная копия ----------

    private val _backup = MutableStateFlow(BackupUiState())
    val backup = _backup.asStateFlow()

    /**
     * Экспорт — один CSV-файл, который человек может унести куда угодно (Drive, письмо, флешка).
     * Пишем через SAF: приложение не просит доступа ко всему хранилищу и не выбирает путь само.
     */
    fun exportTo(uri: Uri?) {
        if (uri == null) return
        val context = container.applicationContext
        _backup.update { it.copy(isBusy = true, message = null, pending = null, plan = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val (products, variants, history) = container.productRepository.exportSnapshot()
                    val text = BackupFormat.encode(BackupFormat.build(products, variants, history))
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("файл не открывается для записи")
                    products.size
                }
            }
            _backup.update {
                outcome.fold(
                    onSuccess = { count ->
                        it.copy(
                            isBusy = false,
                            message = context.getString(R.string.backup_exported, count),
                        )
                    },

                    onFailure = { error ->
                        it.copy(
                            isBusy = false,
                            message = context.getString(R.string.backup_error_write, error.message ?: ""),
                        )
                    },
                )
            }
        }
    }

    /**
     * Импорт читаем и планируем, но НЕ применяем молча: «в файле 3 новых и 1 совпадёт с
     * текущими» — то, что человек должен увидеть до того, как база изменится.
     */
    fun importFrom(uri: Uri?) {
        if (uri == null) return
        _backup.update { it.copy(isBusy = true, message = null, pending = null, plan = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val text = container.applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("файл не читается")
                    BackupFormat.decode(text)
                }
            }
            outcome.fold(
                onSuccess = { decoded ->
                    if (!decoded.isValid) {
                        _backup.update {
                            it.copy(
                                isBusy = false,
                                failure = decoded.failure?.name,
                                message = decoded.details ?: decoded.warnings.firstOrNull(),
                            )
                        }
                        return@launch
                    }
                    val backup = decoded.backup!!
                    val plan = withContext(Dispatchers.IO) { container.productRepository.backupPlan(backup) }
                    _backup.update {
                        it.copy(
                            isBusy = false,
                            pending = backup,
                            plan = plan,
                            failure = null,
                            message = decoded.warnings.firstOrNull(),
                        )
                    }
                },
                onFailure = { error ->
                    _backup.update {
                        it.copy(
                            isBusy = false,
                            message = container.applicationContext.getString(
                                R.string.backup_error_read,
                                error.message ?: "",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun applyImport() {
        val pending = _backup.value.pending ?: return
        val plan = _backup.value.plan ?: return
        val context = container.applicationContext
        _backup.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { container.productRepository.importBackup(pending, plan) }
            }
            outcome.fold(
                onSuccess = { report ->
                    val text = if (report.isNothingToDo) {
                        context.getString(R.string.backup_nothing_to_do)
                    } else {
                        context.getString(
                            R.string.backup_imported,
                            report.inserted,
                            report.updated,
                            report.historyRestored,
                        )
                    }
                    _backup.update { it.copy(isBusy = false, pending = null, plan = null, failure = null, message = text) }
                },
                onFailure = { error ->
                    _backup.update {
                        it.copy(
                            isBusy = false,
                            message = context.getString(R.string.backup_error_interrupted, error.message ?: ""),
                        )
                    }
                },
            )
        }
    }

    fun cancelImport() {
        _backup.update { BackupUiState() }
    }

    companion object {
        private const val LOG_DISPLAY_LIMIT = 25
    }
}

/** Экран показывает либо результат, либо «что будет», если импорт ещё не подтверждён. */
data class BackupUiState(
    val isBusy: Boolean = false,
    /** Файл прочитан и распланирован — ждёт подтверждения. */
    val pending: Backup? = null,
    val plan: BackupPlan? = null,
    val message: String? = null,
    val failure: String? = null,
) {
    val awaitsConfirmation: Boolean get() = pending != null && plan != null
}
