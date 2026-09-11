package com.example.otsled.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.otsled.MainActivity
import com.example.otsled.R

class PriceNotificationManager(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createChannel()
        // Канал заводим сразу: решение о «тихой» доставке принимается ночью, и создавать канал на
        // лету — значит отдать первое уведомление дефолтной важности системы.
        createChannel(CHANNEL_ID_SILENT, "Цены (тихие часы)", NotificationManager.IMPORTANCE_LOW)
    }

    /**
     * [tag] — ключ варианта. Без него все уведомления товара идут с одним id и каждое
     * следующее перезаписывает предыдущее: из изменения цен трёх объёмов пользователь увидел
     * бы только последнее.
     */
    /**
     * [silent] — «в тихие часы»: сообщение кладётся в канал без звука и без всплывающего окна.
     * Именно кладётся, а не выбрасывается: проспать падение цены из-за того, что телефон был
     * на тумбочке, — обиднее, чем молчаливое уведомление в шторке.
     */
    fun showPriceAlert(
        productId: Long,
        title: String,
        message: String,
        tag: String? = null,
        silent: Boolean = false,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PRODUCT_ID, productId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            productId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val channel = if (silent) {
            createChannel(CHANNEL_ID_SILENT, "Цены (тихие часы)", NotificationManager.IMPORTANCE_LOW)
            CHANNEL_ID_SILENT
        } else {
            CHANNEL_ID
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (tag != null) {
            notificationManager.notify(tag, productId.toInt(), notification)
        } else {
            notificationManager.notify(productId.toInt(), notification)
        }
    }

    fun showForegroundNotification(): android.app.Notification {
        createChannel(CHANNEL_FOREGROUND_ID, "Фоновая проверка", NotificationManager.IMPORTANCE_LOW)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.foreground_notification_title))
            .setContentText(context.getString(R.string.foreground_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel(
        channelId: String = CHANNEL_ID,
        name: String = "Изменения цен",
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, name, importance)
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "price_alerts"

        /** Тот же смысл, что и основной канал, но без звука: для тихих часов. */
        const val CHANNEL_ID_SILENT = "price_alerts_quiet"
        const val CHANNEL_FOREGROUND_ID = "price_check_foreground"
        const val EXTRA_PRODUCT_ID = "product_id"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }
}
