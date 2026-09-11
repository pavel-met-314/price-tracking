package com.example.otsled.data.repository

import com.example.otsled.data.db.PriceHistoryEntryEntity
import com.example.otsled.data.db.ProductDao
import com.example.otsled.data.db.ProductVariantEntity
import com.example.otsled.data.db.TrackedProductEntity
import com.example.otsled.data.parser.ParsedProductVariant
import com.example.otsled.domain.Backup
import com.example.otsled.domain.BackupItemAction
import com.example.otsled.domain.BackupMerge
import com.example.otsled.domain.BackupHistoryRow
import com.example.otsled.domain.BackupPlan
import com.example.otsled.domain.BackupVariantRow
import com.example.otsled.domain.ProductEdit
import com.example.otsled.domain.model.PriceCheckLog
import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.ProductVariant
import com.example.otsled.domain.model.TrackedProduct
import com.example.otsled.domain.model.toDomain
import com.example.otsled.domain.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProductRepository(
    private val productDao: ProductDao,
) {
    fun observeProducts(): Flow<List<TrackedProduct>> =
        productDao.observeProducts().map { list -> list.map { it.toDomain() } }

    fun observeProduct(id: Long): Flow<TrackedProduct?> =
        productDao.observeProduct(id).map { it?.toDomain() }

    fun observeVariants(productId: Long): Flow<List<ProductVariant>> =
        productDao.observeVariants(productId).map { list -> list.map { it.toDomain() } }

    fun observeHistory(productId: Long): Flow<List<PriceHistoryEntry>> =
        productDao.observeHistory(productId).map { list -> list.map { it.toDomain() } }

    fun observeLog(limit: Int = LOG_LIMIT): Flow<List<PriceCheckLog>> =
        productDao.observeLog(limit).map { list -> list.map { it.toDomain() } }

    fun observeProductIds(): Flow<List<Long>> = productDao.observeProductIds()

    /** История сразу по всем товарам — чтобы список считал пометку об изменении цены одним запросом. */
    fun observeHistoryForProducts(ids: List<Long>): Flow<List<PriceHistoryEntry>> =
        productDao.observeHistoryForProducts(ids).map { list -> list.map { it.toDomain() } }

    suspend fun getActiveProducts(): List<TrackedProduct> =
        productDao.getActiveProducts().map { it.toDomain() }

    /** И архивные тоже — см. комментарий к DAO: этот список про «не плодить дубли», а не про «что проверять». */
    suspend fun getAllProducts(): List<TrackedProduct> =
        productDao.getAllProducts().map { it.toDomain() }

    suspend fun getProduct(id: Long): TrackedProduct? =
        productDao.getProduct(id)?.toDomain()

    suspend fun getVariants(productId: Long): List<ProductVariant> =
        productDao.getVariants(productId).map { it.toDomain() }

    /** Варианты, которые сейчас реально есть на странице товара — без снятых с продажи. */
    suspend fun getTrackedVariants(productId: Long): List<ProductVariant> =
        productDao.getTrackedVariants(productId).map { it.toDomain() }

    suspend fun insertProduct(product: TrackedProduct): Long =
        productDao.insertProduct(product.toEntity())

    suspend fun updateProduct(product: TrackedProduct) {
        productDao.updateProduct(product.toEntity())
    }

    /**
     * В архив (`at` != null) или обратно (`at == null`). История цен и сама запись не трогаются:
     * «разонравилось» и «ошиблись товаром» — разные действия, и второе остаётся отдельной кнопкой.
     */
    suspend fun setArchived(productId: Long, at: Long?) {
        productDao.setArchivedAt(id = productId, archivedAt = at)
    }

    suspend fun setActive(productId: Long, active: Boolean) {
        productDao.setActive(id = productId, active = active)
    }

    suspend fun deleteProduct(product: TrackedProduct) {
        productDao.deleteProduct(product.toEntity())
        productDao.deleteLogForProduct(product.id)
    }

    /**
     * Синхронизация вариантов со страницей: найденные обновляются, исчезнувшие помечаются
     * неактуальными, но не удаляются — на них ссылается история цен.
     */
    suspend fun replaceVariants(
        productId: Long,
        parsedVariants: List<ParsedProductVariant>,
        checkedAt: Long,
    ): List<ProductVariant> {
        val existingByKey = productDao.getVariants(productId).associateBy { it.variantKey }
        val result = mutableListOf<ProductVariant>()
        val seenIds = mutableListOf<Long>()

        parsedVariants.forEach { parsed ->
            val existing = existingByKey[parsed.variantKey()]
            val entity = parsed.toEntity(productId, checkedAt).copy(
                id = existing?.id ?: 0,
            )
            val id = if (existing == null) {
                productDao.insertVariant(entity)
            } else {
                productDao.updateVariant(entity.copy(id = existing.id))
                existing.id
            }
            seenIds += id
            result += entity.copy(id = id).toDomain()
        }

        // Пустой список сюда приходит только при осознанном «цены не найдены», и снимать
        // тогда все варианты было бы самоубийством для истории.
        if (seenIds.isNotEmpty()) {
            productDao.untrackMissingVariants(productId, seenIds)
        }

        return result
    }

    suspend fun markCheckSuccess(productId: Long, title: String, lastPrice: Double, checkedAt: Long) {
        productDao.markCheckSuccess(
            id = productId,
            title = title,
            lastPrice = lastPrice,
            checkedAt = checkedAt,
        )
    }

    suspend fun markCheckFailure(productId: Long, checkedAt: Long, errorCode: String, errorMessage: String?) {
        productDao.markCheckFailure(
            id = productId,
            checkedAt = checkedAt,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    suspend fun insertHistory(entry: PriceHistoryEntry): Long =
        productDao.insertHistory(entry.toEntity())

    suspend fun logCheck(entry: PriceCheckLog) {
        productDao.insertLog(entry.toEntity())
        productDao.pruneLog(LOG_LIMIT)
    }


    /**
     * Ручная правка товара. При смене ссылки обнуляем цену и убираем варианты с историей:
     * «цена от» и график, построенные по старой странице, враньё про новую — ровно тот же класс
     * ошибки, что и разные объёмы на одной оси.
     */
    suspend fun applyEdit(productId: Long, edit: ProductEdit) {
        val current = productDao.getProduct(productId)
        productDao.updateEditableFields(
            id = productId,
            url = edit.url,
            titleOverride = edit.titleOverride,
            targetPrice = edit.targetPrice,
            notifyAnyChange = edit.notifyOnAnyChange,
            notifyTargetReached = edit.notifyOnTargetReached,
            lastPrice = if (edit.pageChanged) null else current?.lastPrice,
        )
        if (edit.pageChanged) {
            productDao.deleteVariantsForProduct(productId)
            productDao.deleteHistoryForProduct(productId)
        }
    }

    /** Отпечаток разметки и подозрение на её смену — см. domain/LayoutChangeDetection. */
    suspend fun setLayoutInfo(productId: Long, fingerprint: String?, note: String) {
        productDao.setLayoutInfo(id = productId, fingerprint = fingerprint, note = note)
    }

    // ---------- выборки для виджета (синхронные: у AppWidgetProvider нет корутины) ----------

    fun getActiveProductsBlocking(): List<TrackedProduct> =
        productDao.getActiveProductsBlocking().map { it.toDomain() }

    fun getHistoryForProductsBlocking(ids: List<Long>): List<PriceHistoryEntry> =
        if (ids.isEmpty()) emptyList() else productDao.getHistoryForProductsBlocking(ids).map { it.toDomain() }

    // ---------- резервная копия ----------

    suspend fun exportSnapshot(): Triple<List<TrackedProduct>, List<ProductVariant>, List<PriceHistoryEntry>> =
        Triple(
            productDao.getAllProducts().map { it.toDomain() },
            productDao.getAllVariantsBlocking().map { it.toDomain() },
            productDao.getAllHistoryBlocking().map { it.toDomain() },
        )

    suspend fun backupPlan(backup: Backup): BackupPlan =
        BackupMerge.plan(productDao.getAllProducts().map { it.url }, backup)

    /**
     * Импорт по плану. Существующий товар обновляется по первичному ключу, а не REPLACE: у
     * `price_history` ключ — `productId` с каскадом, и замена строки с новым id молча стёрла бы
     * всю историю цен, которую копия как раз и пришла спасать.
     */
    suspend fun importBackup(backup: Backup, plan: BackupPlan): ImportReport {
        var inserted = 0
        var updated = 0
        var variants = 0
        var historyRows = 0

        // Снимок существующих товаров — один на весь импорт: новые записи плана не должны влиять
        // на сопоставление последующих, иначе два товара одной ссылки вели бы себя по-разному.
        val existingByUrl = productDao.getAllProducts()
            .associateBy { BackupMerge.canonicalUrl(it.url) }

        plan.items.filter { it.action != BackupItemAction.SKIP }.forEach { item ->
            val row = backup.products.first { BackupMerge.canonicalUrl(it.url) == item.url }
            val existing = existingByUrl[item.url]
            val productId = if (existing == null) {
                inserted++
                productDao.insertProduct(row.toEntity(id = 0))
            } else {
                updated++
                productDao.updateProduct(row.toEntity(id = existing.id))
                existing.id
            }

            variants += restoreVariants(productId, backup.variantsFor(row.url))
            historyRows += restoreHistory(productId, backup.historyFor(row.url))
        }

        return ImportReport(
            inserted = inserted,
            updated = updated,
            variantsRestored = variants,
            historyRestored = historyRows,
            skipped = plan.skipCount,
        )
    }

    private suspend fun restoreVariants(productId: Long, rows: List<BackupVariantRow>): Int {
        if (rows.isEmpty()) return 0
        val known = productDao.getVariants(productId).associateBy { it.variantKey }
        rows.forEach { row ->
            val previous = known[row.variantKey]
            val entity = ProductVariantEntity(
                id = previous?.id ?: 0,
                productId = productId,
                variantKey = row.variantKey,
                volume = row.volume,
                label = row.label,
                article = row.article,
                lastPrice = row.lastPrice,
                oldPrice = row.oldPrice,
                lastCheckedAt = previous?.lastCheckedAt,
                isTracked = row.isTracked,
                lastSeenAt = previous?.lastSeenAt,
            )
            // id сохраняем: на него опирается история цен этого объёма.
            if (previous == null) productDao.insertVariant(entity) else productDao.updateVariant(entity)
        }
        return rows.size
    }

    private suspend fun restoreHistory(productId: Long, rows: List<BackupHistoryRow>): Int {
        if (rows.isEmpty()) return 0
        val variants = productDao.getVariants(productId)
        val variantIds = variants.associate { it.variantKey to it.id }
        val labels = variants.mapNotNull { entity -> entity.toDomain().let { it.variantKey to it.displayName() } }.toMap()
        val known = productDao.getHistoryForProductsBlocking(listOf(productId))
            .map { keyOf(it) }
            .toSet()
        val fresh = rows
            .map { row ->
                PriceHistoryEntryEntity(
                    productId = productId,
                    variantId = variantIds[row.variantKey],
                    // Подпись объёма нужна списку и графику; если вариант в копии не нашёлся,
                    // строка остаётся без подписи — это честно, а не выдуманное «100 мл».
                    volumeLabel = labels[row.variantKey],
                    price = row.price,
                    checkedAt = row.checkedAt,
                )
            }
            .filter { keyOf(it) !in known }
        if (fresh.isNotEmpty()) productDao.insertHistoryEntries(fresh)
        return fresh.size
    }

    /** Чем опознаётся строка истории: товар + объём + цена + момент. Id в копии нет. */
    private fun keyOf(entry: PriceHistoryEntryEntity): Triple<Long, Double, Long> =
        Triple(entry.productId, entry.price, entry.checkedAt)

    /** Итог импорта — его показываем человеку: «что именно изменилось в базе» без цифр не проверить. */
    data class ImportReport(
        val inserted: Int,
        val updated: Int,
        val variantsRestored: Int,
        val historyRestored: Int,
        val skipped: Int,
    ) {
        val isNothingToDo: Boolean get() = inserted == 0 && updated == 0 && historyRestored == 0
    }

    companion object {
        /** Сколько записей журнала крутится в базе. */
        const val LOG_LIMIT = 300
    }
}
