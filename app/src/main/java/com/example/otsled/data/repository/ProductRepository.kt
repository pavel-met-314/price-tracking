package com.example.otsled.data.repository

import com.example.otsled.data.db.ProductDao
import com.example.otsled.domain.model.PriceHistoryEntry
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

    fun observeHistory(productId: Long): Flow<List<PriceHistoryEntry>> =
        productDao.observeHistory(productId).map { list -> list.map { it.toDomain() } }

    suspend fun getActiveProducts(): List<TrackedProduct> =
        productDao.getActiveProducts().map { it.toDomain() }

    suspend fun getProduct(id: Long): TrackedProduct? =
        productDao.getProduct(id)?.toDomain()

    suspend fun insertProduct(product: TrackedProduct): Long =
        productDao.insertProduct(product.toEntity())

    suspend fun updateProduct(product: TrackedProduct) {
        productDao.updateProduct(product.toEntity())
    }

    suspend fun deleteProduct(product: TrackedProduct) {
        productDao.deleteProduct(product.toEntity())
    }

    suspend fun insertHistory(entry: PriceHistoryEntry): Long =
        productDao.insertHistory(entry.toEntity())
}
