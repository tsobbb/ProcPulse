package com.example.procpulse.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfilerDao {
    @Query("SELECT * FROM profiler_sessions ORDER BY startTimeMs DESC")
    fun observeAllSessions(): Flow<List<ProfilerSessionEntity>>

    @Query("SELECT * FROM profiler_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ProfilerSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ProfilerSessionEntity): Long

    @Update
    suspend fun updateSession(session: ProfilerSessionEntity)

    @Query("DELETE FROM profiler_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: ProfilerSampleEntity): Long

    @Query("SELECT * FROM profiler_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun observeSamplesForSession(sessionId: Long): Flow<List<ProfilerSampleEntity>>

    @Query("SELECT * FROM profiler_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getSamplesForSession(sessionId: Long): List<ProfilerSampleEntity>
}

@Dao
interface WatchdogDao {
    @Query("SELECT * FROM watchdog_events ORDER BY timestampMs DESC LIMIT 200")
    fun observeEvents(): Flow<List<WatchdogEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: WatchdogEventEntity): Long

    @Query("DELETE FROM watchdog_events")
    suspend fun clearEvents()
}
