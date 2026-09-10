package com.example.otsled.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.domain.model.PriceCheckLog
import com.example.otsled.ui.AppViewModelFactory
import com.example.otsled.ui.permissions.rememberNotificationPermissionState
import com.example.otsled.util.AppBuildInfo
import com.example.otsled.util.DateFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModelFactory: AppViewModelFactory,
    onBack: () -> Unit,
    onOpenBrowserCheck: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val interval by viewModel.checkIntervalMinutes.collectAsStateWithLifecycle()
    val foregroundEnabled by viewModel.foregroundServiceEnabled.collectAsStateWithLifecycle()
    val checkLog by viewModel.checkLog.collectAsStateWithLifecycle()
    val permissionState = rememberNotificationPermissionState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Отвечает на «а та ли версия на телефоне?»: без этой строки отказ установки по
            // подписи выглядит как «разработчик ничего не сделал».
            Text(
                text = stringResource(R.string.settings_build, AppBuildInfo.describe(LocalContext.current)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            val update by viewModel.update.collectAsStateWithLifecycle()
            UpdateCard(
                state = update,
                onCheck = viewModel::checkForUpdate,
                onDownload = viewModel::downloadUpdate,
                onInstall = viewModel::installDownloaded,
                onOpenPermission = viewModel::openInstallPermissionSettings,
            )

            Text(text = stringResource(R.string.check_interval), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsRepository.INTERVAL_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = interval == option,
                        onClick = { viewModel.setCheckInterval(option) },
                        label = { Text(stringResource(R.string.interval_minutes, option)) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.foreground_service), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.foreground_service_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = foregroundEnabled,
                    onCheckedChange = viewModel::setForegroundServiceEnabled,
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text(
                    text = stringResource(R.string.notifications_permission),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
                if (permissionState.hasPermission) {
                    Text(
                        text = stringResource(R.string.notifications_permission_granted),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Button(
                        onClick = { permissionState.requestPermission() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.notifications_permission))
                    }
                }
            }

            Text(
                text = stringResource(R.string.check_log_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.check_log_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (checkLog.isEmpty()) {
                Text(
                    text = stringResource(R.string.check_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                checkLog.forEach { entry ->
                    CheckLogRow(entry)
                }
            }

            // Если проверка требует человека, сброс кулдауна ничего не даст: сначала её нужно
            // пройти — для этого и есть этот экран.
            OutlinedButton(
                onClick = onOpenBrowserCheck,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.browser_check_open))
            }
            OutlinedButton(
                onClick = viewModel::resetParseSession,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.reset_parse_session))
            }
        }
    }
}

@Composable
private fun CheckLogRow(entry: PriceCheckLog) {
    val failed = entry.status == PriceCheckLog.STATUS_ERROR

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = buildString {
                    append(DateFormatter.format(entry.createdAt))
                    append("  ·  ")
                    append(entry.productId?.let { "#$it" } ?: "—")
                    if (entry.source.isNotBlank()) {
                        append("  ·  ")
                        append(entry.source)
                    }
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = if (failed) {
                    stringResource(R.string.check_log_error, entry.kind, entry.message.orEmpty())
                } else {
                    // Сообщение есть не у каждой проверки: у поиска оно и есть весь результат.
                    entry.message?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.check_log_ok, entry.variantsCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Ручная проверка обновления: «есть новая сборка» и «скачать» — отдельные нажатия, потому что
 * второе стоит ~20 МБ трафика. Ставит файл системный установщик (по URI из кэша) — своего
 * «тихого» обновления тут нет намеренно: приложение не подменяет собой магазин.
 */
@Composable
private fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.update_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = updateStatusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val progress = state.progress
            if (state.stage == UpdateStage.DOWNLOADING && progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.stage) {
                    UpdateStage.AVAILABLE -> Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.update_download, state.release?.versionName ?: ""))
                    }

                    UpdateStage.READY -> Button(
                        onClick = onInstall,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.update_install))
                    }

                    UpdateStage.NEEDS_PERMISSION -> {
                        Button(onClick = onOpenPermission, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.update_open_permission))
                        }
                        OutlinedButton(onClick = onInstall, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.update_install))
                        }
                    }

                    UpdateStage.DOWNLOADING -> Button(
                        onClick = onDownload,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.update_checking))
                    }

                    else -> Button(
                        onClick = onCheck,
                        enabled = state.stage != UpdateStage.CHECKING,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (state.stage == UpdateStage.CHECKING) R.string.update_checking else R.string.update_check,
                            ),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.update_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun updateStatusText(state: UpdateUiState): String {
    val remote = state.release?.versionName
    return when (state.stage) {
        UpdateStage.IDLE -> stringResource(R.string.update_idle)
        UpdateStage.CHECKING -> stringResource(R.string.update_checking)
        UpdateStage.UP_TO_DATE -> stringResource(R.string.update_up_to_date, remote ?: state.localVersion ?: "")
        UpdateStage.AVAILABLE -> stringResource(R.string.update_available, remote ?: "?")
        UpdateStage.DOWNLOADING -> state.progress?.let {
            stringResource(R.string.update_downloading, (it * 100).toInt())
        } ?: stringResource(R.string.update_downloading_unknown)

        UpdateStage.READY -> stringResource(R.string.update_ready)
        UpdateStage.NEEDS_PERMISSION -> stringResource(R.string.update_needs_permission)
        UpdateStage.FAILED -> stringResource(R.string.update_failed, state.message ?: "")
    }
}
