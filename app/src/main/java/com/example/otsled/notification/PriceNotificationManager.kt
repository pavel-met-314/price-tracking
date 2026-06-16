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
    }

    fun showPriceAlert(
        productId: Long,
        title: String,
        message: String,
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(productId.toInt(), notification)
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
        const val CHANNEL_FOREGROUND_ID = "price_check_foreground"
        const val EXTRA_PRODUCT_ID = "product_id"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }
}
