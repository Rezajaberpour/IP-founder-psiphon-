package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CleanIpEntity::class],
    version = 4,
    exportSchema = false
)
abstract class MlmDatabase : RoomDatabase() {
    abstract fun cleanIpDao(): CleanIpDao

    companion object {
        @Volatile
        private var INSTANCE: MlmDatabase? = null

        fun getInstance(context: Context): MlmDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MlmDatabase::class.java,
                    "ipfounder_psiphon.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
