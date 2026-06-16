package com.example.otsled.ui.settings

import android.os.Build
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.ui.AppViewModelFactory
import com.example.otsled.ui.permissions.NotificationPermissionState
import com.example.otsled.ui.permissions.rememberNotificationPermissionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModelFactory: AppViewModelFactory,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val interval by viewModel.checkIntervalMinutes.collectAsStateWithLifecycle()
    val foregroundEnabled by viewModel.foregroundServiceEnabled.collectAsStateWithLifecycle()
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
            Text(text = stringResource(R.string.check_interval), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
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
        }
    }
}
