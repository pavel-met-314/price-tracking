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
    ],
    version = 2,
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

        fun getInstance(context: Context): OtsledDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): OtsledDatabase {
            return Room.databaseBuilder(context, OtsledDatabase::class.java, "otsled.db")
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
