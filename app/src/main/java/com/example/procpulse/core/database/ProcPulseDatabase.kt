package com.example.procpulse.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfilerSessionEntity::class,
        ProfilerSampleEntity::class,
        WatchdogEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ProcPulseDatabase : RoomDatabase() {
    abstract fun profilerDao(): ProfilerDao
    abstract fun watchdogDao(): WatchdogDao

    companion object {
        @Volatile
        private var INSTANCE: ProcPulseDatabase? = null

        fun getInstance(context: Context): ProcPulseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProcPulseDatabase::class.java,
                    "procpulse_blackbox.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
