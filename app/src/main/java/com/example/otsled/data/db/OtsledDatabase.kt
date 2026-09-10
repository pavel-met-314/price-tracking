package com.example.otsled.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackedProductEntity::class,
        ProductVariantEntity::class,
        PriceHistoryEntryEntity::class,
        PriceCheckLogEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class OtsledDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var instance: OtsledDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS product_variants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        productId INTEGER NOT NULL,
                        variantKey TEXT NOT NULL,
                        volume TEXT NOT NULL,
                        label TEXT NOT NULL,
                        article TEXT,
                        lastPrice REAL NOT NULL,
                        oldPrice REAL,
                        lastCheckedAt INTEGER,
                        FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_product_variants_productId_variantKey ON product_variants(productId, variantKey)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_variants_productId ON product_variants(productId)")
                db.execSQL("ALTER TABLE price_history ADD COLUMN variantId INTEGER")
                db.execSQL("ALTER TABLE price_history ADD COLUMN volumeLabel TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_price_history_variantId ON price_history(variantId)")
            }
        }

        /**
         * v3: состояние последних проверок (чтобы UI отличал «цена не менялась» от «парсер сломался»),
         * отметка актуальности варианта и кольцевой журнал диагностики парсинга.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN lastSuccessAt INTEGER")
                db.execSQL("ALTER TABLE products ADD COLUMN lastErrorCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE products ADD COLUMN lastErrorMessage TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN consecutiveFailures INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE product_variants ADD COLUMN isTracked INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE product_variants ADD COLUMN lastSeenAt INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS price_check_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        productId INTEGER,
                        status TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        source TEXT NOT NULL,
                        message TEXT,
                        variantsCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_price_check_log_createdAt ON price_check_log(createdAt)")
            }
        }

        /**
         * v4: архив вместо удаления. Товар, который «больше не интересен», не нужно стирать вместе
         * с историей цен — достаточно убрать из проверок и из общего списка.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN archivedAt INTEGER")
            }
        }

        fun getInstance(context: Context): OtsledDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): OtsledDatabase {
            return Room.databaseBuilder(context, OtsledDatabase::class.java, "otsled.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
