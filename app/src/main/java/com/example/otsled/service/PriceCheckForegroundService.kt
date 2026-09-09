package com.example.otsled.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.otsled.OtsledApplication
import com.example.otsled.notification.PriceNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PriceCheckForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val container = (application as OtsledApplication).container
        val notification = container.notificationManager.showForegroundNotification()
        startForeground(PriceNotificationManager.FOREGROUND_NOTIFICATION_ID, notification)

        checkJob?.cancel()
        checkJob = serviceScope.launch {
            val intervalMinutes = container.settingsRepository.getCheckIntervalMinutes()
                .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
            while (isActive) {
                // Одна упавшая проверка не должна убивать бесконечный цикл: без runCatching
                // служба продолжала висеть с уведомлением, молча не проверяя цены.
                runCatching { container.priceCheckUseCase.checkAllActiveProducts() }
                delay(intervalMinutes * 60_000L)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        checkJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.otsled.action.STOP_PRICE_CHECK"

        /** Разумные границы «проверять каждые N минут» для ручного режима. */
        private const val MIN_INTERVAL_MINUTES = 5
        private const val MAX_INTERVAL_MINUTES = 720

        fun start(context: Context) {
            val intent = Intent(context, PriceCheckForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PriceCheckForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
