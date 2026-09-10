package com.example.otsled.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.ui.AppViewModelFactory
import com.example.otsled.domain.model.ProductStatus
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.domain.model.isPaused
import com.example.otsled.domain.model.status
import com.example.otsled.util.DateFormatter
import com.example.otsled.util.PriceFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    viewModelFactory: AppViewModelFactory,
    productId: Long,
    onBack: () -> Unit,
    onClosed: () -> Unit,
) {
    val viewModel: ProductDetailViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return viewModelFactory.createProductDetailViewModel(productId) as T
            }
        },
    )
    val product by viewModel.product.collectAsStateWithLifecycle()
    val variants by viewModel.variants.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val selectedVariantId by viewModel.selectedVariant.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.closed) {
        if (uiState.closed) onClosed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(product?.title ?: "...") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (product == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val current = product!!
                Column {
                    Text(text = current.url, style = MaterialTheme.typography.bodySmall)
                    StatusNotice(current)
                    current.targetPrice?.let { target ->
                        Text(
                            text = stringResource(R.string.target_price_label, PriceFormatter.formatPrice(target)),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    current.lastCheckedAt?.let { checkedAt ->
                        Text(
                            text = stringResource(R.string.last_checked, DateFormatter.format(checkedAt)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = stringResource(R.string.volume_prices),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    if (variants.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_price_yet),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        VariantPricesList(variants = variants)
                    }
                    PriceHistoryCard(
                        series = overview.series,
                        variants = variants,
                        selectedVariantId = selectedVariantId,
                        onSelectVariant = viewModel::selectVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Button(
                        onClick = viewModel::checkNow,
                        enabled = !uiState.isChecking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.check_now))
                    }
                    // Порядок кнопок — по возрастанию необратимости: архив всегда под рукой,
                    // «удалить навсегда» отдельным нажатием внизу.
                    OutlinedButton(
                        onClick = {
                            if (current.isArchived) viewModel.restoreFromArchive() else viewModel.moveToArchive()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            stringResource(
                                if (current.isArchived) R.string.action_restore else R.string.action_archive,
                            ),
                        )
                    }
                    TextButton(
                        onClick = viewModel::togglePaused,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            stringResource(
                                if (current.isPaused) R.string.action_resume else R.string.action_pause,
                            ),
                        )
                    }
                    TextButton(
                        onClick = viewModel::deleteProduct,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete_forever),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    uiState.errorMessage?.let { error ->
                        Text(
                            text = stringResource(R.string.parse_error, error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (uiState.errorMessage == null && current.hasCheckProblem) {
                        Text(
                            text = stringResource(
                                R.string.last_error_label,
                                current.lastErrorMessage ?: current.lastErrorCode,
                                current.consecutiveFailures,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (uiState.isChecking) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    }
                    Text(
                        text = stringResource(R.string.price_history),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
            }
            items(overview.history, key = { it.id }) { entry ->
                HistoryRow(entry)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: PriceHistoryEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            entry.volumeLabel?.let { label ->
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = stringResource(R.string.price_rubles, PriceFormatter.formatPrice(entry.price)),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = DateFormatter.format(entry.checkedAt),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Что приложение думает о товаре по последним проверкам — на этом экране важно не только «цена
 * упала», но и «цену мы достать не смогли, потому что …». Подпись исчезает вместе с проблемой:
 * врать о товаре по сетевому сбою хуже, чем молчать.
 */
@Composable
private fun StatusNotice(product: TrackedProduct) {
    val lines = buildList {
        when (product.status) {
            ProductStatus.OK, ProductStatus.NO_DATA -> Unit
            ProductStatus.OUT_OF_STOCK -> add(stringResource(R.string.status_out_of_stock))
            ProductStatus.NOT_FOUND -> add(stringResource(R.string.status_not_found))
            ProductStatus.PARSE_FAILED -> add(stringResource(R.string.status_parse_failed))
            ProductStatus.ACCESS_FAILED -> add(
                if (product.isBotBlocked) {
                    stringResource(R.string.problem_bot_blocked)
                } else {
                    stringResource(R.string.status_access_failed)
                },
            )
        }
        if (product.status.concernsProduct && product.lastPrice != null) {
            add(stringResource(R.string.status_stale_price))
        }
        if (product.isPaused) {
            add(
                if (product.consecutiveFailures >= PAUSE_HINT_AFTER) {
                    stringResource(R.string.status_paused_auto, product.consecutiveFailures)
                } else {
                    stringResource(R.string.status_paused)
                },
            )
        }
        product.archivedAt?.let { add(stringResource(R.string.status_archived, DateFormatter.format(it))) }
    }
    if (lines.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (product.status.isProblem) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private const val PAUSE_HINT_AFTER = 3
