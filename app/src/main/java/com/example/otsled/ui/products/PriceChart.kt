package com.example.otsled.ui.products

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otsled.R
import com.example.otsled.domain.PricePoint
import com.example.otsled.domain.PriceSeries
import com.example.otsled.domain.model.ProductVariant
import com.example.otsled.util.DateFormatter
import com.example.otsled.util.PriceFormatter

/** Цена упала — зелёный, выросла — красный. Динамическая тема для этого сигнала слишком «мягкая». */
private val FallingColor = Color(0xFF1B8A5A)
private val RisingColor = Color(0xFFC0392B)

/**
 * Один цвет для всех сигналов о цене: и на графике, и в пометке «с прошлой проверки» в списке.
 * Два места с разным смыслом стрелки — это «зелёный тут значит плохое».
 */
@Composable
fun priceTrendColor(delta: Double): Color = when {
    delta < 0.0 -> FallingColor
    delta > 0.0 -> RisingColor
    else -> MaterialTheme.colorScheme.primary
}

/**
 * Линейный график цен на чистом Canvas. Сторонняя chart-библиотека ради одной кривой добавила бы
 * 1.5 МБ и ещё одну точку несовместимости с Compose, поэтому рисуем сами. Ось Y нормируется по
 * данным с запасом 12%, чтобы линия не липла к краям карточки, а ось X — по времени записей,
 * иначе редкие проверки «растягиваются» и недавнее падение выглядит пологим.
 */
@Composable
fun PriceChart(
    points: List<PricePoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        if (points.isEmpty()) return@Canvas

        val leftPad = 6.dp.toPx()
        val rightPad = 6.dp.toPx()
        val topPad = 10.dp.toPx()
        val bottomPad = 10.dp.toPx()
        val chartWidth = size.width - leftPad - rightPad
        val chartHeight = size.height - topPad - bottomPad

        val prices = points.map { it.price }
        val lowest = prices.min()
        val highest = prices.max()
        val spread = highest - lowest
        val margin = if (spread > 0) spread * 0.12 else (highest * 0.05).coerceAtLeast(1.0)
        val top = highest + margin
        val bottom = (lowest - margin).coerceAtLeast(0.0)
        val yRange = (top - bottom).takeIf { it > 0.0 } ?: 1.0

        val firstTime = points.first().checkedAt
        val lastTime = points.last().checkedAt
        val single = points.size == 1
        val timeSpan = (lastTime - firstTime).coerceAtLeast(1L).toFloat()

        val xs = points.map { point ->
            if (single) leftPad + chartWidth / 2f
            else leftPad + (point.checkedAt - firstTime).toFloat() / timeSpan * chartWidth
        }
        val ys = points.map { point ->
            if (single) topPad + chartHeight / 2f
            else topPad + ((top - point.price) / yRange * chartHeight).toFloat()
        }

        // Верхняя и нижняя границы диапазона — по ним глаз оценивает «насколько высоко сидит цена».
        val grid = Path().apply {
            moveTo(leftPad, topPad)
            lineTo(size.width - rightPad, topPad)
            moveTo(leftPad, size.height - bottomPad)
            lineTo(size.width - rightPad, size.height - bottomPad)
        }
        // Штриховка живёт внутри Stroke: у drawPath отдельного pathEffect-параметра нет.
        drawPath(
            path = grid,
            color = gridColor,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
            ),
        )

        if (!single) {
            val line = Path()
            xs.forEachIndexed { index, x ->
                val y = ys[index]
                if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            val area = Path().apply {
                addPath(line)
                lineTo(xs.last(), size.height - bottomPad)
                lineTo(xs.first(), size.height - bottomPad)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.26f), lineColor.copy(alpha = 0.02f)),
                    startY = topPad,
                    endY = size.height - bottomPad,
                ),
            )
            drawPath(
                path = line,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        var minIndex = 0
        var maxIndex = 0
        prices.forEachIndexed { index, price ->
            if (price < prices[minIndex]) minIndex = index
            if (price > prices[maxIndex]) maxIndex = index
        }
        points.forEachIndexed { index, _ ->
            if (index != minIndex && index != maxIndex && !single) {
                drawCircle(color = lineColor, radius = 2.dp.toPx(), center = Offset(xs[index], ys[index]))
            }
        }
        drawCircle(color = FallingColor, radius = 3.5.dp.toPx(), center = Offset(xs[minIndex], ys[minIndex]))
        drawCircle(color = RisingColor, radius = 3.5.dp.toPx(), center = Offset(xs[maxIndex], ys[maxIndex]))

        val lastIndex = xs.lastIndex
        drawCircle(color = onSurfaceColor, radius = 4.5.dp.toPx(), center = Offset(xs[lastIndex], ys[lastIndex]))
        drawCircle(color = surfaceColor, radius = 2.dp.toPx(), center = Offset(xs[lastIndex], ys[lastIndex]))
    }
}

/**
 * Блок «График цены»: линия по одному объёму, подписи минимума и максимума у оси и три цифры —
 * минимум, максимум и изменение с прошлой проверки. История в БД пишется только на изменение
 * цены, поэтому «последняя проверка» здесь = последняя запись в истории, и дата подписана явно.
 *
 * Режима «все объёмы вместе» нет намеренно: сравнивать 2 мл и 100 мл на одной шкале — значит
 * рисовать рост цены там, где цена падала.
 */
@Composable
fun PriceHistoryCard(
    series: PriceSeries,
    variants: List<ProductVariant>,
    selectedVariantId: Long?,
    onSelectVariant: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trendColor = if (series.hasTrend) {
        priceTrendColor(series.deltaFromPrevious ?: 0.0)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val selectableVariants = variants.filter { it.isTracked }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.price_chart_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                // График всегда про один объём — пишем какой, иначе цифры ниже оси невозможно
                // отнести к конкретной цене на странице.
                selectableVariants.firstOrNull { it.id == selectedVariantId }?.let { variant ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = variant.displayName(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (selectableVariants.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectableVariants.forEach { variant ->
                        FilterChip(
                            selected = selectedVariantId == variant.id,
                            onClick = { onSelectVariant(variant.id) },
                            label = { Text(variant.displayName()) },
                        )
                    }
                }
            }

            if (!series.hasTrend) {
                Text(
                    text = stringResource(R.string.price_chart_needs_points),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Слева — «от» (минимум, зелёный), справа — «до» (максимум, красный): строка
                // читается как диапазон цен, и порядок в ней важнее, чем позиция точек на линии.
                series.min?.let {
                    Text(
                        text = stringResource(R.string.price_chart_min_value, PriceFormatter.formatPrice(it.price)),
                        style = MaterialTheme.typography.labelMedium,
                        color = FallingColor,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                series.max?.let {
                    Text(
                        text = stringResource(R.string.price_chart_max_value, PriceFormatter.formatPrice(it.price)),
                        style = MaterialTheme.typography.labelMedium,
                        color = RisingColor,
                    )
                }
            }

            PriceChart(
                points = series.points,
                modifier = Modifier.padding(top = 4.dp),
                lineColor = trendColor,
            )

            val first = series.points.firstOrNull()
            val last = series.points.lastOrNull()
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = first?.let { DateFormatter.formatShort(it.checkedAt) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = last?.let { DateFormatter.formatShort(it.checkedAt) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                series.min?.let {
                    StatCell(
                        label = stringResource(R.string.price_chart_min),
                        value = stringResource(R.string.price_rubles, PriceFormatter.formatPrice(it.price)),
                        sub = DateFormatter.formatShort(it.checkedAt),
                        modifier = Modifier.weight(1f),
                    )
                }
                series.max?.let {
                    StatCell(
                        label = stringResource(R.string.price_chart_max),
                        value = stringResource(R.string.price_rubles, PriceFormatter.formatPrice(it.price)),
                        sub = DateFormatter.formatShort(it.checkedAt),
                        modifier = Modifier.weight(1f),
                    )
                }
                series.average?.let {
                    StatCell(
                        label = stringResource(R.string.price_chart_average),
                        value = stringResource(R.string.price_rubles, PriceFormatter.formatPrice(it)),
                        sub = null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            series.deltaFromPrevious?.let { delta ->
                val percent = series.previous?.let { prev ->
                    series.current?.let { PriceFormatter.formatPercent(prev.price, it.price) }
                }
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.price_chart_delta_previous),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            append(PriceFormatter.formatSigned(delta))
                            append(" ₽")
                            percent?.let { append(" · ") ; append(it) }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = trendColor,
                    )
                }
                val previousPoint = series.previous
                val currentPoint = series.current
                if (previousPoint != null && currentPoint != null) {
                    val period = DateFormatter.formatShort(previousPoint.checkedAt) +
                        " → " + DateFormatter.formatShort(currentPoint.checkedAt)
                    Text(
                        text = period,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (series.droppedPoints > 0) {
                Text(
                    text = stringResource(R.string.price_chart_truncated, series.droppedPoints),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
