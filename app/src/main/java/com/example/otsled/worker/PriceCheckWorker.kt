package com.example.otsled.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.otsled.OtsledApplication
import com.example.otsled.data.settings.SettingsRepository
import com.example.otsled.ui.widget.DroppedPriceWidget
import java.util.concurrent.TimeUnit

class PriceCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OtsledApplication).container

        // checkProduct() не бросает исключения, а возвращает ParseResult.Error, поэтому решение
        // о повторе принимается по итогу прогона: ретраим, только если не удалось ничего.
        return runCatching { container.priceCheckUseCase.checkAllActiveProducts() }
            .fold(
                onSuccess = { outcome ->
                    // Виджет сам на базу не подписан: обновляем его руками после каждого цикла,
                    // иначе на домашнем экране неделями висели бы вчерашние цены.
                    DroppedPriceWidget.pushUpdate(applicationContext)
                    if (outcome.shouldRetry) Result.retry() else Result.success()
                },
                onFailure = {
                    DroppedPriceWidget.pushUpdate(applicationContext)
                    Result.retry()
                },
            )
    }

    companion object {
        const val WORK_NAME = "price_check_periodic"
    }
}

class PriceCheckScheduler(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    fun schedulePeriodicCheck() {
        // WorkManager не умеет интервалы короче 15 минут, поэтому нижняя граница именно такая.
        val intervalMinutes = settingsRepository.getCheckIntervalMinutes()
            .coerceAtLeast(MIN_INTERVAL_MINUTES)
            .toLong()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<PriceCheckWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
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

    companion object {
        private const val MIN_INTERVAL_MINUTES = 15

        /** Пауза перед повтором после неудачного прогона (растёт экспоненциально от этого значения). */
        private const val BACKOFF_MINUTES = 5L
    }
}
