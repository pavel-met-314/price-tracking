package com.example.otsled.domain

import com.example.otsled.data.parser.ProductUrlNormalizer

enum class BackupItemAction { INSERT, UPDATE, SKIP }

data class BackupPlanItem(val url: String, val action: BackupItemAction)

/**
 * Что импорт сделает с базой. Отдельно от Room, потому что решение «это тот же товар или новый»
 * — единственное место, где можно испортить данные молча: неверный ответ либо удвоит историю, либо
 * затрёт отслеживание, которое человек настраивал руками.
 */
data class BackupPlan(val items: List<BackupPlanItem>) {
    val insertCount: Int get() = items.count { it.action == BackupItemAction.INSERT }
    val updateCount: Int get() = items.count { it.action == BackupItemAction.UPDATE }
    val skipCount: Int get() = items.count { it.action == BackupItemAction.SKIP }
    val affectedCount: Int get() = insertCount + updateCount
    val isEmpty: Boolean get() = items.isEmpty()

    fun urls(action: BackupItemAction): List<String> = items.filter { it.action == action }.map { it.url }
}

object BackupMerge {

    /** Ключ товара в копии — нормализованная ссылка: id в новом телефоне будут другие. */
    fun canonicalUrl(raw: String): String = ProductUrlNormalizer.normalize(raw) ?: raw.trim()

    fun plan(existingUrls: List<String>, backup: Backup): BackupPlan {
        val known = existingUrls.mapTo(mutableSetOf()) { canonicalUrl(it) }
        val seen = mutableSetOf<String>()

        return BackupPlan(
            backup.products.map { row ->
                val url = canonicalUrl(row.url)
                val action = when {
                    // Дубль внутри самого файла: вторую запись той же ссылки молча применять нельзя —
                    // она стёрла бы то, что внесла первая.
                    !seen.add(url) -> BackupItemAction.SKIP
                    url in known -> BackupItemAction.UPDATE
                    else -> BackupItemAction.INSERT
                }
                BackupPlanItem(url, action)
            },
        )
    }

    /**
     * Строка истории опознаётся по товару, объёму, цене и моменту. Id не годятся: в копии их нет, а
     * повторный импорт того же файла не должен удваивать каждый пункт графика.
     */
    data class HistoryKey(val productUrl: String, val variantKey: String, val price: Double, val checkedAt: Long)

    fun newHistoryKeys(existing: Set<HistoryKey>, incoming: List<HistoryKey>): List<HistoryKey> {
        val added = mutableSetOf<HistoryKey>()
        return incoming.filter { it !in existing && added.add(it) }
    }
}
