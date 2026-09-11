package com.example.otsled.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Прогон «обновить всё», запущенный вручную. Живёт вне ViewModel, потому что проверка крутится в
 * службе и переживает уход с экрана: состояние должно остаться тем же, когда человек вернётся.
 */
data class ManualCheckProgress(
    val done: Int,
    val total: Int,
    /** Сколько товаров не влезло в бюджет цикла — их догонит плановая проверка. */
    val deferred: Int,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val everythingChecked: Boolean get() = deferred == 0
}

object ManualCheckState {
    private val _progress = MutableStateFlow<ManualCheckProgress?>(null)
    val progress: StateFlow<ManualCheckProgress?> = _progress.asStateFlow()

    val isRunning: Boolean get() = _progress.value != null

    /**
     * Повторный тап не должен перезапускать прогон: иначе десять нажатий подряд превратились бы в
     * десять волн запросов к сайту — ровно то, за что анти-бот и закрывает доступ.
     */
    fun begin(total: Int): Boolean {
        if (_progress.value != null) return false
        _progress.value = ManualCheckProgress(done = 0, total = total, deferred = 0)
        return true
    }

    fun advance(progress: ManualCheckProgress) {
        if (_progress.value != null) _progress.value = progress
    }

    fun finish() {
        _progress.value = null
    }
}
