package com.example.otsled.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * План импорта и дедупликация истории. Импорт — единственная операция в приложении, которая может
 * испортить данные молча и массово, поэтому «что я собираюсь сделать» считается отдельной
 * чистой функцией и показывается человеку до применения.
 */
class BackupMergeTest {

    private val urlA = "https://allureparfum.ru/katalog/creed/aventus.html"
    private val urlB = "https://allureparfum.ru/katalog/byredo/ganymede.html"

    private fun productRow(url: String) = BackupProductRow(
        url = url,
        title = url.substringAfterLast('/').removeSuffix(".html"),
        titleOverride = null,
        targetPrice = null,
        notifyOnAnyChange = true,
        notifyOnTargetReached = true,
        isActive = true,
        archivedAt = null,
        lastPrice = null,
        lastCheckedAt = null,
        lastSuccessAt = null,
    )

    @Test
    fun existingProductsAreUpdatedNotDuplicated() {
        val plan = BackupMerge.plan(
            existingUrls = listOf(urlA),
            backup = Backup(products = listOf(productRow(urlA), productRow(urlB))),
        )

        assertEquals(BackupItemAction.UPDATE, plan.items[0].action)
        assertEquals(BackupItemAction.INSERT, plan.items[1].action)
        assertEquals(1, plan.insertCount)
        assertEquals(1, plan.updateCount)
        assertEquals(0, plan.skipCount)
    }

    @Test
    fun samePageWithDifferentSpellingIsTheSameProduct() {
        // Товары в копии и в базе сравниваются по нормализованной ссылке: хвост «?» и запятая из
        // вставки в чат — та же страница, и дубль из-за неё заводиться не должен.
        val plan = BackupMerge.plan(
            existingUrls = listOf("$urlA?"),
            backup = Backup(products = listOf(productRow("$urlA, цена 3000"))),
        )

        assertEquals(BackupItemAction.UPDATE, plan.items.single().action)
    }

    @Test
    fun trackingParamsMakeAnotherPage() {
        // Честное следствие правила: нормализация не вырезает query, и ссылка с utm считается
        // другой страницей. Молча слить её с существующей опаснее, чем попросить человека
        // почистить адрес.
        val plan = BackupMerge.plan(
            existingUrls = listOf(urlA),
            backup = Backup(products = listOf(productRow("$urlA?utm_source=tel"))),
        )

        assertEquals(BackupItemAction.INSERT, plan.items.single().action)
    }

    @Test
    fun duplicatesInsideTheFileAreSkippedNotAppliedTwice() {
        val plan = BackupMerge.plan(
            existingUrls = emptyList(),
            backup = Backup(products = listOf(productRow(urlA), productRow(urlA), productRow(urlB))),
        )

        assertEquals(listOf(BackupItemAction.INSERT, BackupItemAction.SKIP, BackupItemAction.INSERT), plan.items.map { it.action })
        assertEquals(1, plan.skipCount)
        assertEquals(2, plan.affectedCount)
    }

    @Test
    fun planUrlsAreCanonicalForLookUp() {
        val plan = BackupMerge.plan(emptyList(), Backup(products = listOf(productRow("$urlA, цена 3000"))))

        assertEquals(listOf(urlA), plan.urls(BackupItemAction.INSERT))
    }

    @Test
    fun historyAlreadyPresentIsNotImportedTwice() {
        val key = BackupMerge.HistoryKey(urlA, "100 мл", 1_000.0, 5L)
        val incoming = listOf(
            key,
            BackupMerge.HistoryKey(urlA, "100 мл", 900.0, 6L),
            BackupMerge.HistoryKey(urlA, "100 мл", 900.0, 6L),   // дубль внутри самого файла
            BackupMerge.HistoryKey(urlB, "50 мл", 300.0, 7L),
        )

        val fresh = BackupMerge.newHistoryKeys(existing = setOf(key), incoming = incoming)

        assertEquals(2, fresh.size)
        assertTrue(fresh.first().checkedAt == 6L)
        assertEquals(urlB, fresh.last().productUrl)
    }

    @Test
    fun emptyBackupPlansNothing() {
        val plan = BackupMerge.plan(listOf(urlA), Backup())

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.affectedCount)
    }
}
