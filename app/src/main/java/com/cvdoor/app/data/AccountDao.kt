package com.cvdoor.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(acc: UserAccount)

    @Query("SELECT * FROM user_account WHERE userId = :uid")
    suspend fun get(uid: String): UserAccount?

    @Query("""
        UPDATE user_account
        SET remainingCredits = remainingCredits - 1
        WHERE userId = :uid AND remainingCredits > 0
    """)
    suspend fun consume(uid: String)

    @Query("UPDATE user_account SET remainingCredits = remainingCredits + :delta WHERE userId = :uid")
    suspend fun addCredits(uid: String, delta: Int)
}
