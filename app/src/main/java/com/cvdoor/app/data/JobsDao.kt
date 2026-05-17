package com.cvdoor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedJobDao {
    @Query("SELECT * FROM saved_jobs ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: SavedJobEntity): Long

    @Delete
    suspend fun delete(job: SavedJobEntity)

    @Query("DELETE FROM saved_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface JobApplicationDao {
    @Query("SELECT * FROM job_applications WHERE userId = :uid ORDER BY appliedAt DESC")
    fun observeAll(uid: String): Flow<List<JobApplicationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: JobApplicationEntity): Long

    @Update
    suspend fun update(app: JobApplicationEntity)

    @Query("DELETE FROM job_applications WHERE id = :id")
    suspend fun deleteById(id: Long)
}
