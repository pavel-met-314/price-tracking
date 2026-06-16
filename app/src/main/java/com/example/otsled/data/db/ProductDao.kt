package com.example.otsled.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY id DESC")
    fun observeProducts(): Flow<List<TrackedProductEntity>>

    @Query("SELECT * FROM products WHERE isActive = 1")
    suspend fun getActiveProducts(): List<TrackedProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProduct(id: Long): TrackedProductEntity?

    @Query("SELECT * FROM products WHERE id = :id")
    fun observeProduct(id: Long): Flow<TrackedProductEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: TrackedProductEntity): Long

    @Update
    suspend fun updateProduct(product: TrackedProductEntity)

    @Delete
    suspend fun deleteProduct(product: TrackedProductEntity)

    @Query("SELECT * FROM price_history WHERE productId = :productId ORDER BY checkedAt DESC")
    fun observeHistory(productId: Long): Flow<List<PriceHistoryEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: PriceHistoryEntryEntity): Long
}
