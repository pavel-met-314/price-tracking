package com.example.otsled.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackedProductEntity::class, PriceHistoryEntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OtsledDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var instance: OtsledDatabase? = null

        fun getInstance(context: Context): OtsledDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OtsledDatabase::class.java,
                    "otsled.db",
                ).build().also { instance = it }
            }
        }
    }
}
