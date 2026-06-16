package com.example.otsled.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.example.otsled.OtsledApplication
import java.util.concurrent.TimeUnit

class PriceCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OtsledApplication).container
        return runCatching {
            container.priceCheckUseCase().checkAllActiveProducts()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        const val WORK_NAME = "price_check_periodic"
    }
}

class PriceCheckScheduler(
    private val context: Context,
    private val settingsRepository: com.example.otsled.data.settings.SettingsRepository,
) {
    fun schedulePeriodicCheck() {
        val intervalMinutes = settingsRepository.getCheckIntervalMinutes()
            .coerceAtLeast(15)
            .toLong()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<PriceCheckWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PriceCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodicCheck() {
        WorkManager.getInstance(context).cancelUniqueWork(PriceCheckWorker.WORK_NAME)
    }
}
