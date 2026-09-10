package com.example.otsled.ui.products

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    val state by viewModel.state.collectAsStateWithLifecycle()

    val sortOptions = sortOptions()
    val filterOptions = filterOptions()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.products_title))
                        if (state.totalCount > 0) {
                            Text(
                                text = if (state.showsAll) {
                                    stringResource(R.string.products_list_count, state.totalCount)
                                } else {
                                    stringResource(R.string.products_list_count_filtered, state.rows.size, state.totalCount)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Выбор порядка и фильтра живёт над списком и не уезжает при прокрутке: сменить
            // фильтр, не найдя сначала «куда делись товары», — то, чем список неудобнее всего.
            if (state.totalCount > 0) {
                ChoiceRow(
                    label = stringResource(R.string.products_sort_label),
                    options = sortOptions.map { option -> stringResource(option.second) },
                    selectedIndex = sortOptions.indexOfFirst { it.first == state.sort },
                    onSelect = { index -> viewModel.onSortSelected(sortOptions[index].first) },
                )
                ChoiceRow(
                    label = stringResource(R.string.products_filter_label),
                    options = filterOptions.map { option -> stringResource(option.second) },
                    selectedIndex = filterOptions.indexOfFirst { it.first == state.filter },
                    onSelect = { index -> viewModel.onFilterSelected(filterOptions[index].first) },
                )
            }

            when {
                state.isEmptyList -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.empty_products), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.empty_products_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                state.isFilterEmpty -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.products_empty_after_filter),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedButton(
                            onClick = viewModel::resetFilter,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.products_reset_filter))
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.rows, key = { it.product.id }) { row ->
                        ProductCard(row = row, onClick = { onOpenProduct(row.product.id) })
                    }
                }
            }
        }
    }
}

/**
 * Одна строка выбора: подпись и чипы. Порядок вариантов задаёт экран, поэтому выбор передаётся
 * индексом — сравнивать подписи строкой значило бы сделать тексты частью контракта.
 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEachIndexed { index, text ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(text) },
            )
        }
    }
}

private fun sortOptions(): List<Pair<ProductSort, Int>> = listOf(
    ProductSort.ADDED to R.string.sort_added,
    ProductSort.NAME to R.string.sort_name,
    ProductSort.PRICE_ASC to R.string.sort_price_asc,
    ProductSort.PRICE_DESC to R.string.sort_price_desc,
    ProductSort.DROP to R.string.sort_drop,
    ProductSort.STALE to R.string.sort_stale,
)

private fun filterOptions(): List<Pair<ProductFilter, Int>> = listOf(
    ProductFilter.ALL to R.string.filter_all,
    ProductFilter.BELOW_TARGET to R.string.filter_below_target,
    ProductFilter.DROPPED to R.string.filter_dropped,
    ProductFilter.PROBLEM to R.string.filter_problem,
    ProductFilter.NO_PRICE to R.string.filter_no_price,
)



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
