package com.example.procpulse.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfilerSessionEntity::class,
        ProfilerSampleEntity::class,
        WatchdogEventEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ProcPulseDatabase : RoomDatabase() {
    abstract fun profilerDao(): ProfilerDao
    abstract fun watchdogDao(): WatchdogDao

    companion object {
        @Volatile
        private var INSTANCE: ProcPulseDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiler_sessions ADD COLUMN isLagDiagnostic INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN pswpinPerSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN pswpoutPerSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN pgmajfaultPerSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN allocstallPerSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN memAvailableMb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN ioWaitPercent REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN ioBusyPercent REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN procsRunning INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN procsBlocked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN isCpuCapped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN scalingMaxSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN amKillEventsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN foregroundPackage TEXT")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN jankPercent REAL")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN jankStage TEXT")
                db.execSQL("ALTER TABLE profiler_samples ADD COLUMN rendererType TEXT")
            }
        }

        fun getInstance(context: Context): ProcPulseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProcPulseDatabase::class.java,
                    "procpulse_blackbox.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
