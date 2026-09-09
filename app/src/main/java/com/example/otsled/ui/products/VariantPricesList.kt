package com.example.otsled.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.otsled.R
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.domain.model.ProductVariant
import kotlin.math.roundToInt

/**
 * Цены по объёмам. Отдельно показываем цену за миллилитр: именно она объясняет, почему
 * флакон 100 мл выгоднее двух по 50 мл, и сразу видно явную ошибку парсера (0 или 2 ₽/мл).
 */
@Composable
fun VariantPricesList(
    variants: List<ProductVariant>,
    modifier: Modifier = Modifier,
) {
    variants.forEach { variant ->
        val perMl = variant.pricePerMl()?.let { pricePerMl ->
            stringResource(R.string.price_per_ml, formatPrice((pricePerMl * 10).roundToInt() / 10.0))
        }
        val staleLabel = if (!variant.isTracked) stringResource(R.string.variant_untracked) else null

        VariantPriceRow(
            line = variant.priceLine(),
            perMl = perMl,
            staleLabel = staleLabel,
            modifier = modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun ParsedVariantPricesList(
    variants: List<ParsedProductVariant>,
    modifier: Modifier = Modifier,
) {
    variants.forEach { variant ->
        VariantPriceRow(
            line = variant.priceLine(),
            perMl = null,
            staleLabel = null,
            modifier = modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun VariantPriceRow(
    line: String,
    perMl: String?,
    staleLabel: String?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
            if (perMl != null || staleLabel != null) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    perMl?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    staleLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun formatPrice(price: Double): String {
    return if (price % 1.0 == 0.0) price.toLong().toString() else price.toString()
}
