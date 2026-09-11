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

    /**
     * Товары, которые надо проверять: не в архиве и не на паузе. Архив исключается запросом, а не
     * фильтром в памяти: иначе список из сорока «архивных» ссылок каждый час жёг бы трафик и
     * батарею ровно так же, как до архива.
     */
    @Query("SELECT * FROM products WHERE isActive = 1 AND archivedAt IS NULL")
    suspend fun getActiveProducts(): List<TrackedProductEntity>

    /**
     * Все товары, включая архивные и на паузе. Нужен поиску, чтобы он распознавал уже отслеживаемую
     * страницу: иначе «добавить» архивный товар создал бы дубль и снёс `archivedAt` вместе с
     * привязанной историей (у Room ключ — URL, REPLACE перечёркивает запись целиком).
     */
    @Query("SELECT * FROM products ORDER BY id DESC")
    suspend fun getAllProducts(): List<TrackedProductEntity>

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

    /** null — вернуть из архива. Отдельный запрос, чтобы не перезаписывать цены целиком. */
    @Query("UPDATE products SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun setArchivedAt(id: Long, archivedAt: Long?)

    /** Пауза проверок: запись, цены и история остаются на месте, но сеть не тратится. */
    @Query("UPDATE products SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    /**
     * У утерянного товара цена может остаться «валидной» из старой записи, поэтому при успехе
     * пишем и цену, и сброс статуса одним запросом — отдельный updateProduct перезаписал бы
     * счётчик неудач старым значением.
     */
    @Query(
        """
        UPDATE products
        SET title = :title,
            lastPrice = :lastPrice,
            lastCheckedAt = :checkedAt,
            lastSuccessAt = :checkedAt,
            lastErrorCode = '',
            lastErrorMessage = NULL,
            consecutiveFailures = 0
        WHERE id = :id
        """,
    )
    suspend fun markCheckSuccess(id: Long, title: String, lastPrice: Double, checkedAt: Long)

    @Query(
        """
        UPDATE products
        SET lastCheckedAt = :checkedAt,
            lastErrorCode = :errorCode,
            lastErrorMessage = :errorMessage,
            consecutiveFailures = consecutiveFailures + 1
        WHERE id = :id
        """,
    )
    suspend fun markCheckFailure(id: Long, checkedAt: Long, errorCode: String, errorMessage: String?)

    @Query("SELECT * FROM product_variants WHERE productId = :productId ORDER BY lastPrice ASC")
    fun observeVariants(productId: Long): Flow<List<ProductVariantEntity>>

    @Query("SELECT * FROM product_variants WHERE productId = :productId")
    suspend fun getVariants(productId: Long): List<ProductVariantEntity>

    @Query("SELECT * FROM product_variants WHERE productId = :productId AND isTracked = 1")
    suspend fun getTrackedVariants(productId: Long): List<ProductVariantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariant(variant: ProductVariantEntity): Long

    @Update
    suspend fun updateVariant(variant: ProductVariantEntity)

    /**
     * Объём исчез со страницы товара. Удалять строку нельзя — на неё ссылается история цен,
     * поэтому variant помечается неактуальным и перестаёт влиять на «цену от» и уведомления.
     */
    @Query("UPDATE product_variants SET isTracked = 0 WHERE productId = :productId AND id NOT IN (:seenIds)")
    suspend fun untrackMissingVariants(productId: Long, seenIds: List<Long>)

    @Query("SELECT * FROM price_history WHERE productId = :productId ORDER BY checkedAt DESC")
    fun observeHistory(productId: Long): Flow<List<PriceHistoryEntryEntity>>

    /**
     * id нужны отдельным потоком, чтобы список товаров мог подписаться на историю «всех сразу»
     * одним запросом вместо N подписок: на десятках товаров это заметно по лишний прогонам SQL.
     */
    @Query("SELECT id FROM products")
    fun observeProductIds(): Flow<List<Long>>

    @Query("SELECT * FROM price_history WHERE productId IN (:ids) ORDER BY checkedAt DESC")
    fun observeHistoryForProducts(ids: List<Long>): Flow<List<PriceHistoryEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: PriceHistoryEntryEntity): Long

    @Insert
    suspend fun insertLog(entry: PriceCheckLogEntity): Long

    @Query("SELECT * FROM price_check_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeLog(limit: Int): Flow<List<PriceCheckLogEntity>>

    @Query("SELECT * FROM price_check_log ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastLogEntry(): PriceCheckLogEntity?

    /** Журнал кольцевой: держим не больше [keep] записей, иначе база разрастается бесконечно. */
    @Query(
        """
        DELETE FROM price_check_log
        WHERE id NOT IN (SELECT id FROM price_check_log ORDER BY createdAt DESC LIMIT :keep)
        """,
    )
    suspend fun pruneLog(keep: Int)

    @Query("DELETE FROM price_check_log WHERE productId = :productId")
    suspend fun deleteLogForProduct(productId: Long)

    /**
     * Ручная правка товара. Что сбрасывать при смене ссылки решает domain (ProductEditing), здесь
     * просто пишем то, что ему сказали: цену передают null, когда страница стала другой.
     */
    @Query(
        """
        UPDATE products
        SET url = :url,
            titleOverride = :titleOverride,
            targetPrice = :targetPrice,
            notifyOnAnyChange = :notifyAnyChange,
            notifyOnTargetReached = :notifyTargetReached,
            lastPrice = :lastPrice,
            lastFingerprint = NULL,
            lastLayoutNote = '',
            lastErrorCode = '',
            lastErrorMessage = NULL,
            consecutiveFailures = 0
        WHERE id = :id
        """,
    )
    suspend fun updateEditableFields(
        id: Long,
        url: String,
        titleOverride: String?,
        targetPrice: Double?,
        notifyAnyChange: Boolean,
        notifyTargetReached: Boolean,
        lastPrice: Double?,
    )

    /** Отпечаток разметки и заметка о том, что в ней перестало находиться (см. domain/LayoutFingerprint). */
    @Query("UPDATE products SET lastFingerprint = :fingerprint, lastLayoutNote = :note WHERE id = :id")
    suspend fun setLayoutInfo(id: Long, fingerprint: String?, note: String)

    @Query("DELETE FROM product_variants WHERE productId = :productId")
    suspend fun deleteVariantsForProduct(productId: Long)

    @Query("DELETE FROM price_history WHERE productId = :productId")
    suspend fun deleteHistoryForProduct(productId: Long)

    // Синхронные запросы — для виджета и экспорта: они вызываются с фонового потока, где вешать
    // корутины не на что (AppWidgetProvider живёт миллисекунды).
    @Query("SELECT * FROM products WHERE isActive = 1 AND archivedAt IS NULL ORDER BY id DESC")
    fun getActiveProductsBlocking(): List<TrackedProductEntity>

    @Query("SELECT * FROM price_history WHERE productId IN (:ids)")
    fun getHistoryForProductsBlocking(ids: List<Long>): List<PriceHistoryEntryEntity>

    @Query("SELECT * FROM product_variants")
    fun getAllVariantsBlocking(): List<ProductVariantEntity>

    @Query("SELECT * FROM price_history")
    fun getAllHistoryBlocking(): List<PriceHistoryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryEntries(entries: List<PriceHistoryEntryEntity>)
}
