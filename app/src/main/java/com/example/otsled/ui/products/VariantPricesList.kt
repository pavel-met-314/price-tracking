package com.example.otsled.ui.products

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.domain.model.ProductVariant

@Composable
fun VariantPricesList(
    variants: List<ProductVariant>,
    modifier: Modifier = Modifier,
) {
    variants.forEach { variant ->
        VariantPriceRow(
            line = variant.priceLine(),
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
            modifier = modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun VariantPriceRow(
    line: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
