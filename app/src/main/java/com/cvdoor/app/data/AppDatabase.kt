package com.cvdoor.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OptimizationRecordEntity::class, UserAccount::class, SavedResumeEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(IntListConverters::class, StringListConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun accountDao(): AccountDao
    abstract fun savedResumeDao(): SavedResumeDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE optimization_records ADD COLUMN userId TEXT NOT NULL DEFAULT 'local-user'"
                )
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_account (
                        userId TEXT NOT NULL PRIMARY KEY,
                        remainingCredits INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    UPDATE optimization_records
                    SET createdAt = createdAt * 1000
                    WHERE createdAt < 31536000000
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_resumes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        content TEXT NOT NULL,
                        uploadDate INTEGER NOT NULL,
                        optimizationCount INTEGER NOT NULL DEFAULT 0,
                        industry TEXT NOT NULL DEFAULT '',
                        targetRole TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
            }
        }

        fun get(ctx: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "cvdoor.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
