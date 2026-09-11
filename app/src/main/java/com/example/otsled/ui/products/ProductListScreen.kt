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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.domain.ManualCheckState
import com.example.otsled.domain.VariantPriceChange
import com.example.otsled.service.PriceCheckForegroundService
import com.example.otsled.domain.model.ProductStatus
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.domain.model.isPaused
import com.example.otsled.domain.model.status
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
                        if (state.allCount > 0) {
                            Text(
                                text = if (state.showsAll) {
                                    stringResource(R.string.products_list_count, state.totalCount)
                                } else {
                                    // Режим назван прямо в заголовке: «Архив · 0 из 1» видно сразу,
                                    // и это не выглядит как «пропали все товары».
                                    stringResource(
                                        R.string.products_list_mode_count,
                                        stringResource(modeNameRes(state.filter)),
                                        state.rows.size,
                                        state.totalCount,
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // Ручной прогон всего списка: кнопка активна, пока ничего не идёт, — иначе
                    // десяток нажатий превратился бы в десяток волн запросов к сайту.
                    val context = LocalContext.current
                    val progress by ManualCheckState.progress.collectAsStateWithLifecycle()
                    IconButton(
                        onClick = { PriceCheckForegroundService.checkOnce(context) },
                        enabled = progress == null,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.check_all))
                    }
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
            // Условие — по всем товарам, а не по текущему режиму: иначе пустой «Архив» лишал
            // человека единственной кнопки возврата к списку.
            if (state.allCount > 0) {
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

            val manualProgress by ManualCheckState.progress.collectAsStateWithLifecycle()
            manualProgress?.let { progress ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.check_all_running, progress.done, progress.total),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                    }
                    if (!progress.everythingChecked) {
                        Text(
                            text = stringResource(R.string.check_all_deferred, progress.deferred),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            when {
                state.isFilterEmpty && state.showsArchive -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.products_empty_archive),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.products_empty_archive_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        OutlinedButton(
                            onClick = { viewModel.onFilterSelected(ProductFilter.ALL) },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.products_show_tracked))
                        }
                    }
                }

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
    ProductFilter.ARCHIVE to R.string.filter_archive,
)

/**
 * Подпись статуса товара под ценой.
 *
 * `OK` и `NO_DATA` молчат намеренно: «всё хорошо» в списке неинформативно, а «цену ещё не
 * проверяли» уже сказано строкой цены. Дублировать состояние двумя подписями — способ вырастить
 * список, в котором никто не читает подписи.
 */
@Composable
private fun TrackedProduct.statusText(): String? = when (status) {
    ProductStatus.OK, ProductStatus.NO_DATA -> null

    ProductStatus.OUT_OF_STOCK -> stringResource(R.string.status_out_of_stock)

    ProductStatus.NOT_FOUND -> stringResource(R.string.status_not_found)

    // «Не разобрали страницу» показывает счётчик: одна неудача — случайность, три — вёрстка.
    ProductStatus.PARSE_FAILED -> stringResource(R.string.problem_checks_failed, consecutiveFailures)

    ProductStatus.ACCESS_FAILED -> if (isBotBlocked) {
        stringResource(R.string.problem_bot_blocked)
    } else {
        stringResource(R.string.status_access_failed)
    }
}

/** Имя режима для заголовка: чип «В архив» — действие, а в подписи нужно место («Архив»). */
private fun modeNameRes(filter: ProductFilter): Int = when (filter) {
    ProductFilter.ARCHIVE -> R.string.filter_archive_name
    else -> filterOptions().first { it.first == filter }.second
}

/**
 * Компактная строка изменений: самые крупные по модулю, остальные — счётчиком. Стрелка вниз —
 * подешевело, вверх — подорожало; объём подписан, потому что цена у каждого своя.
 */
private fun List<VariantPriceChange>.priceMarkText(): String {
    val shown = take(MAX_CHANGES_IN_MARK).joinToString(" · ") { change ->
        val arrow = if (change.isFalling) "↓" else "↑"
        val label = if (change.label.isBlank()) "" else "${change.label} "
        "$arrow $label${PriceFormatter.formatSigned(change.delta)} ₽"
    }
    return if (size > MAX_CHANGES_IN_MARK) "$shown · ещё ${size - MAX_CHANGES_IN_MARK}" else shown
}

private const val MAX_CHANGES_IN_MARK = 2

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
                text = product.displayName.ifBlank { product.url },
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
            // Пометка «цена менялась» вместо графика: здесь важна сама новость, а линия,
            // сравнивающая разные объёмы, только вводила в заблуждение. Подробности — в карточке.
            if (row.changes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.price_change_mark, row.changes.priceMarkText()),
                    style = MaterialTheme.typography.labelSmall,
                    color = priceTrendColor(row.changes.first().delta),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (product.hasLayoutSuspicion) {
                Text(
                    text = layoutSuspicionText(product.layoutNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Отдельно про проблему: «цена не менялась» и «мы не смогли получить цену» —
            // для пользователя это разные ситуации, молча показывать старую цену нельзя.
            product.statusText()?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Цена есть, а товара на странице нет — показываем цифру, но называем её тем, что она
            // есть на самом деле: последней известной, а не актуальной.
            if (product.status.concernsProduct && product.lastPrice != null) {
                Text(
                    text = stringResource(R.string.status_stale_price),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (product.isPaused) {
                Text(
                    text = stringResource(R.string.status_paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            product.archivedAt?.let { archivedAt ->
                Text(
                    text = stringResource(R.string.status_archived, DateFormatter.format(archivedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
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
