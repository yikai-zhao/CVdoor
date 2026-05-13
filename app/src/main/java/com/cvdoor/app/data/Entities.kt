package com.cvdoor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/* -------- List<Int> <-> String 转换器（给 Room 用） -------- */
class IntListConverters {
    @TypeConverter
    fun fromString(value: String?): List<Int> =
        value?.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()

    @TypeConverter
    fun toString(list: List<Int>?): String =
        list?.joinToString(",") ?: ""
}

/* -------- List<String> <-> String 转换器 -------- */
class StringListConverters {
    @TypeConverter
    fun fromString(value: String?): List<String> =
        value?.takeIf { it.isNotBlank() }
            ?.split("\u241f")
            ?.map { it.trim() }
            ?: emptyList()

    @TypeConverter
    fun toString(list: List<String>?): String =
        list?.joinToString("\u241f") ?: ""
}

/* -------- Room 实体：历史记录表 -------- */
@Entity(tableName = "optimization_records")
@TypeConverters(IntListConverters::class)
data class OptimizationRecordEntity(
    val userId: String,
    val resumeText: String,
    val jdText: String,
    val optimizedText: String,
    val beforeTotal: Int,
    val afterTotal: Int,
    val dimsBefore: List<Int>,
    val dimsAfter: List<Int>,
    val createdAt: Long,
    @PrimaryKey(autoGenerate = true) val id: Long = 0L
)

/* -------- Room 实体：账户表 -------- */
@Entity(tableName = "user_account")
data class UserAccount(
    @PrimaryKey val userId: String,
    val remainingCredits: Int = 0
)

/* -------- Room 实体：保存的简历库 -------- */
@Entity(tableName = "saved_resumes")
data class SavedResumeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val content: String,
    val uploadDate: Long = System.currentTimeMillis(),
    val optimizationCount: Int = 0,
    val industry: String = "",
    val targetRole: String = ""
)
