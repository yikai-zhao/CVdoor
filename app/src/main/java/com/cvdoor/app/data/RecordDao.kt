package com.cvdoor.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(rec: OptimizationRecordEntity): Long

    @Query("""
        SELECT * FROM optimization_records
        WHERE userId = :uid
        ORDER BY createdAt DESC, id DESC
    """)
    fun observeAll(uid: String): Flow<List<OptimizationRecordEntity>>

    @Query("SELECT * FROM optimization_records WHERE id = :id AND userId = :uid")
    suspend fun getById(uid: String, id: Long): OptimizationRecordEntity?

    @Query("DELETE FROM optimization_records WHERE id = :id AND userId = :uid")
    suspend fun deleteById(uid: String, id: Long)

    @Query("DELETE FROM optimization_records WHERE userId = :uid")
    suspend fun deleteAllForUser(uid: String)
}
