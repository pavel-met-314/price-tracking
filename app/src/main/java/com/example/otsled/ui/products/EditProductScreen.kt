package com.example.otsled.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.domain.EditField
import com.example.otsled.domain.ProductEditDraft
import com.example.otsled.ui.AppViewModelFactory

/**
 * Экран правки: название, ссылка, цель и уведомления. Поля живут здесь и инициализируются один раз
 * товаром из базы — держать их во ViewModel означало бы, что набранное исчезает при повороте экрана
 * или при фоновом обновлении строки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProductScreen(
    viewModelFactory: AppViewModelFactory,
    productId: Long,
    onBack: () -> Unit,
) {
    // Обычная фабрика этот ViewModel создать не умеет: ему нужен productId. Именно из-за
    // такой проводки экран падал в пустоту при открытии — обёртка ниже обязана быть, а не «на
    // всякий случай».
    val viewModel: EditProductViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return viewModelFactory.createEditProductViewModel(productId) as T
            }
        },
    )
    val product by viewModel.product.collectAsStateWithLifecycle()
    val initial by viewModel.initialDraft.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var title by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var notifyAny by remember { mutableStateOf<Boolean?>(null) }
    var notifyTarget by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(initial) {
        val source = initial ?: return@LaunchedEffect
        if (title == null) title = source.title
        if (url == null) url = source.url
        if (target == null) target = source.targetPrice
        if (notifyAny == null) notifyAny = source.notifyOnAnyChange
        if (notifyTarget == null) notifyTarget = source.notifyOnTargetReached
    }

    fun draft() = ProductEditDraft(
        title = title.orEmpty(),
        url = url.orEmpty(),
        targetPrice = target.orEmpty(),
        notifyOnAnyChange = notifyAny ?: true,
        notifyOnTargetReached = notifyTarget ?: true,
    )

    // Предупреждение считаем по нормализованным ссылкам: «.html?» и «.html» — одна страница,
    // и пугать обнулением истории из-за слэша в конце было бы враньём.
    val pointsToAnotherPage = product != null && url != null &&
        ProductUrlNormalizer.normalize(url.orEmpty()) != ProductUrlNormalizer.normalize(product!!.url)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_product_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.save(draft(), checkAfter = false, onSaved = onBack) },
                    enabled = title != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.edit_save))
                }
                Button(
                    onClick = { viewModel.save(draft(), checkAfter = true, onSaved = onBack) },
                    enabled = title != null && !state.isChecking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isChecking) stringResource(R.string.edit_checking) else stringResource(R.string.edit_save_and_check))
                }
            }
        },
    ) { padding ->
        if (product == null || title == null) {
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = product!!.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = title.orEmpty(),
                onValueChange = {
                    title = it
                    viewModel.draftChanged(draft().copy(title = it))
                },
                label = { Text(stringResource(R.string.edit_title_label)) },
                supportingText = { Text(stringResource(R.string.edit_title_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = url.orEmpty(),
                onValueChange = {
                    url = it
                    viewModel.draftChanged(draft().copy(url = it))
                },
                label = { Text(stringResource(R.string.product_url)) },
                isError = state.problems.containsKey(EditField.URL),
                supportingText = {
                    when (state.problems[EditField.URL]) {
                        null -> if (pointsToAnotherPage) {
                            Text(stringResource(R.string.edit_page_changed_warning))
                        } else {
                            Text(stringResource(R.string.edit_url_hint))
                        }

                        else -> Text(stringResource(R.string.edit_error_url_site))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )

            OutlinedTextField(
                value = target.orEmpty(),
                onValueChange = {
                    target = it
                    viewModel.draftChanged(draft().copy(targetPrice = it))
                },
                label = { Text(stringResource(R.string.target_price)) },
                supportingText = {
                    if (state.problems.containsKey(EditField.TARGET_PRICE)) {
                        Text(stringResource(R.string.edit_error_target))
                    }
                },
                isError = state.problems.containsKey(EditField.TARGET_PRICE),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = notifyAny == true, onCheckedChange = { notifyAny = it })
                Text(stringResource(R.string.edit_notify_any), modifier = Modifier.padding(start = 8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = notifyTarget == true, onCheckedChange = { notifyTarget = it })
                Text(stringResource(R.string.edit_notify_target), modifier = Modifier.padding(start = 8.dp))
            }

            if (pointsToAnotherPage) {
                Text(
                    text = stringResource(R.string.edit_reset_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.checkNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
