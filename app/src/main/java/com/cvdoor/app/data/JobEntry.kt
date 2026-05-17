package com.cvdoor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class JobStage { SAVED, APPLIED, INTERVIEW, OFFER }

@Entity(tableName = "job_entries")
data class JobEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: String,
    val title: String,
    val company: String,
    val jdText: String,
    val stage: String = JobStage.SAVED.name,
    val atsScore: Int = 0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface JobEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: JobEntry): Long

    @Update
    suspend fun update(job: JobEntry)

    @Query("DELETE FROM job_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM job_entries WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeAll(userId: String): Flow<List<JobEntry>>

    @Query("SELECT * FROM job_entries WHERE userId = :userId AND stage = :stage ORDER BY createdAt DESC")
    fun observeByStage(userId: String, stage: String): Flow<List<JobEntry>>

    @Query("SELECT * FROM job_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): JobEntry?
}
