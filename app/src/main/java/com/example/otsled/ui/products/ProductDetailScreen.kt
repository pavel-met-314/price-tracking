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
import com.example.otsled.util.DateFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    viewModelFactory: AppViewModelFactory,
    productId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
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

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onDeleted()
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
                    current.targetPrice?.let { target ->
                        Text(
                            text = stringResource(R.string.target_price_label, formatPrice(target)),
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
                    OutlinedButton(
                        onClick = viewModel::deleteProduct,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.delete_product))
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
            Text(text = "${formatPrice(entry.price)} ₽", style = MaterialTheme.typography.titleSmall)
            Text(
                text = DateFormatter.format(entry.checkedAt),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatPrice(price: Double): String {
    return if (price % 1.0 == 0.0) price.toLong().toString() else price.toString()
}
