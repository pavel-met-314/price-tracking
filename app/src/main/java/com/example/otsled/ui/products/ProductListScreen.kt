package com.example.otsled.ui.products

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.ui.AppViewModelFactory
import com.example.otsled.util.DateFormatter
import com.example.otsled.util.PriceFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListScreen(
    viewModelFactory: AppViewModelFactory,
    onAddProduct: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProduct: (Long) -> Unit,
) {
    val viewModel: ProductListViewModel = viewModel(factory = viewModelFactory)
    val rows by viewModel.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.products_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProduct) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_product))
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.empty_products), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.empty_products_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.product.id }) { row ->
                    ProductCard(row = row, onClick = { onOpenProduct(row.product.id) })
                }
            }
        }
    }
}

@Composable
private fun ProductCard(
    row: ProductListRow,
    onClick: () -> Unit,
) {
    val product = row.product
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = product.title.ifBlank { product.url },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val priceText = product.lastPrice?.let { minPrice ->
                stringResource(R.string.price_from, PriceFormatter.formatPrice(minPrice))
            } ?: stringResource(R.string.no_price_yet)
            Text(
                text = priceText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Динамика прямо в списке: ради этого не нужно открывать карточку каждого товара.
            if (row.points.size >= 2) {
                PriceSparkline(
                    points = row.points,
                    modifier = Modifier.padding(top = 8.dp),
                    color = priceTrendColor(row.trendDelta ?: 0.0),
                )
            }
            // Отдельно про проблему: «цена не менялась» и «мы не смогли получить цену» —
            // для пользователя это разные ситуации, молча показывать старую цену нельзя.
            when {
                product.isBotBlocked -> Text(
                    text = stringResource(R.string.problem_bot_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                product.hasCheckProblem -> Text(
                    text = stringResource(R.string.problem_checks_failed, product.consecutiveFailures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            product.targetPrice?.let { target ->
                Text(
                    text = stringResource(R.string.target_price_label, PriceFormatter.formatPrice(target)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            product.lastCheckedAt?.let { checkedAt ->
                Text(
                    text = stringResource(R.string.last_checked, DateFormatter.format(checkedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
