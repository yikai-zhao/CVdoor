package com.cvdoor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedResumeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(resume: SavedResumeEntity): Long

    @Query("SELECT * FROM saved_resumes ORDER BY uploadDate DESC")
    fun observeAll(): Flow<List<SavedResumeEntity>>

    @Query("SELECT * FROM saved_resumes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SavedResumeEntity?

    @Query("DELETE FROM saved_resumes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("""
        UPDATE saved_resumes
        SET optimizationCount = optimizationCount + 1,
            industry = :industry,
            targetRole = :role
        WHERE id = :id
    """)
    suspend fun incrementAndUpdateMeta(id: Long, industry: String, role: String)
}
