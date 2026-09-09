package com.example.otsled.data.repository

import com.example.otsled.data.db.ProductDao
import com.example.otsled.data.parser.ParsedProductVariant
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

    /** История сразу по всем товарам — для мини-графиков в списке. */
    fun observeHistoryForProducts(ids: List<Long>): Flow<List<PriceHistoryEntry>> =
        productDao.observeHistoryForProducts(ids).map { list -> list.map { it.toDomain() } }

    suspend fun getActiveProducts(): List<TrackedProduct> =
        productDao.getActiveProducts().map { it.toDomain() }

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

    companion object {
        /** Сколько записей журнала крутится в базе. */
        const val LOG_LIMIT = 300
    }
}
