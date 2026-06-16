package com.example.otsled.data.repository

import com.example.otsled.data.db.ProductDao
import com.example.otsled.data.parser.ParsedProductVariant
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

    suspend fun getActiveProducts(): List<TrackedProduct> =
        productDao.getActiveProducts().map { it.toDomain() }

    suspend fun getProduct(id: Long): TrackedProduct? =
        productDao.getProduct(id)?.toDomain()

    suspend fun getVariants(productId: Long): List<ProductVariant> =
        productDao.getVariants(productId).map { it.toDomain() }

    suspend fun insertProduct(product: TrackedProduct): Long =
        productDao.insertProduct(product.toEntity())

    suspend fun updateProduct(product: TrackedProduct) {
        productDao.updateProduct(product.toEntity())
    }

    suspend fun deleteProduct(product: TrackedProduct) {
        productDao.deleteProduct(product.toEntity())
    }

    suspend fun replaceVariants(
        productId: Long,
        parsedVariants: List<ParsedProductVariant>,
        checkedAt: Long,
    ): List<ProductVariant> {
        val existingByKey = productDao.getVariants(productId).associateBy { it.variantKey }
        val result = mutableListOf<ProductVariant>()

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
            result += entity.copy(id = id).toDomain()
        }

        return result
    }

    suspend fun insertHistory(entry: PriceHistoryEntry): Long =
        productDao.insertHistory(entry.toEntity())
}
