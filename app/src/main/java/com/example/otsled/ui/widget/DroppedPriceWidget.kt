package com.example.otsled.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.otsled.MainActivity
import com.example.otsled.R
import com.example.otsled.OtsledApplication
import com.example.otsled.domain.DroppedWidget
import com.example.otsled.domain.DroppedWidgetModel
import com.example.otsled.domain.VariantPriceChanges
import com.example.otsled.domain.WidgetPriceRow
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.notification.PriceNotificationManager
import com.example.otsled.util.PriceFormatter
import kotlin.concurrent.thread

/**
 * Виджет «что подешевело» на домашнем экране.
 *
 * Здесь только раскладка. Отбор товаров, падение относительно пика и обрезка по длинам живут в
 * чистых `DroppedWidget` и `VariantPriceChanges` и покрыты тестами: у виджета нет ни отладчика,
 * ни возможности проверить вывод с телефона, так что единственная защита от вранья на домашнем
 * экране — юнит-тесты.
 */
class DroppedPriceWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Провайдер обязан вернуться немедленно, а запрос к базе — не мгновенный: отсюда рабочий
        // поток и goAsync(), чтобы система не оборвала показ на середине.
        val pending = goAsync()
        thread {
            try {
                render(context, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repository = (context.applicationContext as OtsledApplication).container.productRepository
        val products = repository.getActiveProductsBlocking()
        val history = repository.getHistoryForProductsBlocking(products.map { it.id })
        val model = model(products, history)

        ids.forEach { id -> manager.updateAppWidget(id, views(context, model)) }
    }

    private fun views(context: Context, model: DroppedWidgetModel): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.widget_dropped_prices)
        val rows = RemoteViews(context.packageName, R.layout.widget_dropped_rows)
        rows.removeAllViews(R.id.widget_rows)

        if (model.isEmpty) {
            rows.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            rows.setText(R.id.widget_empty, context.getString(R.string.widget_empty))
        } else {
            rows.setViewVisibility(R.id.widget_empty, View.GONE)
            model.rows.forEach { row ->
                val line = RemoteViews(context.packageName, R.layout.widget_price_row)
                line.setText(R.id.widget_row_title, row.title)
                line.setText(R.id.widget_row_delta, deltaLine(context, row))
                line.setText(R.id.widget_row_price, format(row.price))
                // Тап по строке ведёт в карточку: «подешевело» без возможности посмотреть детали —
                // это дразнилка, а не инструмент.
                line.setOnClickPendingIntent(R.id.widget_row, openProduct(context, row.productId))
                rows.addView(R.id.widget_rows, line)
            }
            if (model.hiddenCount > 0) {
                rows.setViewVisibility(R.id.widget_more, View.VISIBLE)
                rows.setText(R.id.widget_more, context.getString(R.string.widget_more, model.hiddenCount))
            } else {
                rows.setViewVisibility(R.id.widget_more, View.GONE)
            }
        }

        root.addView(R.id.widget_content, rows)
        return root
    }

    private fun openProduct(context: Context, productId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // CLEAR_TASK, а не просто NEW_TASK: если приложение уже открыто, система доставила бы
            // тап в onNewIntent, а deep-link на карточку читается в onCreate — тап молча
            // показал бы список вместо товара.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(PriceNotificationManager.EXTRA_PRODUCT_ID, productId)
        }
        return PendingIntent.getActivity(
            context,
            (productId + WIDGET_REQUEST_OFFSET).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun format(price: Double): String = PriceFormatter.formatPrice(price) + " ₽"

    /** «100 мл · -8,5 %»: без подписи объёма строка враньё — цена одного объёма — не цена товара. */
    private fun deltaLine(context: Context, row: WidgetPriceRow): String {
        val percent = PriceFormatter.formatPercent(row.peakPrice, row.price).orEmpty()
        return if (row.volumeLabel.isBlank()) {
            percent
        } else {
            context.getString(R.string.widget_row_delta, row.volumeLabel, percent)
        }
    }

    companion object {

        private const val WIDGET_REQUEST_OFFSET = 10_000L

        /**
         * Кандидаты и правила отбора — здесь, вне Android API, чтобы это можно было проверить:
         * «подешевело» считается по падению одного объёма относительно его же пика.
         */
        fun model(products: List<TrackedProduct>, history: List<PriceHistoryEntry>): DroppedWidgetModel {
            val byProduct = history.groupBy { it.productId }
            return DroppedWidget.build(
                products.map { product ->
                    DroppedWidget.Candidate(
                        productId = product.id,
                        title = product.displayName.ifBlank { product.url },
                        price = product.lastPrice,
                        drop = VariantPriceChanges.bestDrop(byProduct[product.id].orEmpty()),
                    )
                },
            )
        }

        /** Пересобрать виджет после прогона: сам он на базу не подписан. */
        fun pushUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DroppedPriceWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, DroppedPriceWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
