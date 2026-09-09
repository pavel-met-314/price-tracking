package com.example.otsled.ui.products

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.data.site.SiteSearchHit
import com.example.otsled.ui.AppViewModelFactory
import com.example.otsled.util.PriceFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductScreen(
    viewModelFactory: AppViewModelFactory,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenBrowserCheck: () -> Unit,
) {
    val viewModel: AddProductViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_product)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = viewModel::checkNow,
                        enabled = !uiState.isLoading && uiState.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.check_now))
                    }
                    Button(
                        onClick = viewModel::saveProduct,
                        enabled = !uiState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SearchBlock(
                query = uiState.searchQuery,
                isSearching = uiState.isSearching,
                hits = uiState.searchHits,
                message = uiState.searchMessage,
                note = uiState.searchNote,
                retryable = uiState.searchRetryable,
                viaWebView = uiState.searchViaWebView,
                hasSearched = uiState.hasSearched,
                onQueryChange = viewModel::onSearchQueryChange,
                onSearch = viewModel::runSearch,
                onClear = viewModel::clearSearch,
                onPick = viewModel::useSearchHit,
                onOpenBrowserCheck = onOpenBrowserCheck,
            )

            OutlinedTextField(
                value = uiState.url,
                onValueChange = viewModel::onUrlChange,
                label = { Text(stringResource(R.string.product_url_or_search)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.targetPrice,
                onValueChange = viewModel::onTargetPriceChange,
                label = { Text(stringResource(R.string.target_price)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                singleLine = true,
            )

            SettingSwitchRow(
                label = stringResource(R.string.notify_on_change),
                checked = uiState.notifyOnAnyChange,
                onCheckedChange = viewModel::onNotifyOnChangeToggle,
            )
            SettingSwitchRow(
                label = stringResource(R.string.notify_on_target),
                checked = uiState.notifyOnTargetReached,
                onCheckedChange = viewModel::onNotifyOnTargetToggle,
            )

            if (uiState.title.isNotBlank()) {
                Text(
                    text = uiState.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (uiState.parsedVariants.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.volume_prices),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                ParsedVariantPricesList(variants = uiState.parsedVariants)
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = stringResource(R.string.parse_error, error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp, bottom = 16.dp))
            }
        }
    }
}

/**
 * Поиск по названию. Строк намеренно немного и они идут обычным списком внутри прокручиваемой
 * колонки: ленивый список внутри вертикального скролла — это гарантированный «нет высоты» баг.
 */
@Composable
private fun SearchBlock(
    query: String,
    isSearching: Boolean,
    hits: List<SiteSearchHit>,
    message: String?,
    note: String?,
    retryable: Boolean,
    viaWebView: Boolean,
    hasSearched: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onPick: (SiteSearchHit) -> Unit,
    onOpenBrowserCheck: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.search_title),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
                enabled = !isSearching,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (query.isNotBlank()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
            OutlinedButton(
                onClick = onSearch,
                enabled = !isSearching && query.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Text(
                    text = stringResource(R.string.search_button),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (isSearching) {
                Text(
                    text = stringResource(R.string.search_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (retryable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = onSearch, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.search_retry))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onOpenBrowserCheck, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.browser_check_open))
                    }
                }
            }

            if (note != null) {
                // Тот самый текст, который нужно прислать, если «на телефоне пусто, а в браузере
                // есть»: выделяется долгим тапом, чтобы его можно было скопировать целиком.
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (hits.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.search_found, hits.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (viaWebView) {
                    Text(
                        text = stringResource(R.string.search_via_webview),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                hits.forEach { hit ->
                    SearchHitRow(hit = hit, onSelect = { onPick(hit) })
                }
            } else if (!isSearching && hasSearched && message == null) {
                Text(
                    text = stringResource(R.string.search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SiteSearchHit, onSelect: () -> Unit) {
    val priceText = hit.price?.let { stringResource(R.string.search_price, PriceFormatter.formatPrice(it)) }
        ?: stringResource(R.string.search_no_price)
    val details = listOfNotNull(priceText, hit.volumeLabel).joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.search_add_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
