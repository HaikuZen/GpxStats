package com.januarius.gpxstats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY startTimeMillis IS NULL, startTimeMillis DESC, name ASC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT DISTINCT activity FROM tracks ORDER BY activity")
    fun observeActivities(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Track?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Query("UPDATE tracks SET activity = :activity WHERE name = :name")
    suspend fun setActivity(name: String, activity: String)

    @Query("DELETE FROM tracks WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM tracks WHERE name IN (:names)")
    suspend fun deleteByNames(names: List<String>)

    /**
     * Runs an arbitrary pragma such as `PRAGMA wal_checkpoint(FULL)` so the on-disk
     * `.db` file is complete before it is copied for sharing.
     */
    @RawQuery
    fun runPragma(query: SupportSQLiteQuery): Int
}
