package com.cvdoor.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class Repository(ctx: Context) {
    private val db = AppDatabase.get(ctx)
    private val recordDao = db.recordDao()
    private val accountDao = db.accountDao()

    /* ---------------- 記錄 ---------------- */

    suspend fun saveRecord(rec: OptimizationRecord): Long = withContext(Dispatchers.IO) {
        recordDao.insert(rec.normalizedForSave().toEntity())
    }

    fun observeHistory(uid: String): Flow<List<OptimizationRecord>> =
        recordDao.observeAll(uid).map { list -> list.map { it.toUi().fixedForView() } }

    suspend fun getById(uid: String, id: Long): OptimizationRecord? = withContext(Dispatchers.IO) {
        recordDao.getById(uid, id)?.toUi()?.fixedForView()
    }

    suspend fun deleteRecord(uid: String, id: Long) = withContext(Dispatchers.IO) {
        recordDao.deleteById(uid, id)
    }

    suspend fun clearHistory(uid: String) = withContext(Dispatchers.IO) {
        recordDao.deleteAllForUser(uid)
    }

    /* ---------------- 賬戶 ---------------- */

    suspend fun ensureAccount(uid: String) = withContext(Dispatchers.IO) {
        if (accountDao.get(uid) == null) {
            accountDao.upsert(UserAccount(userId = uid, remainingCredits = 0))
        }
    }

    suspend fun getAccount(uid: String): UserAccount? = withContext(Dispatchers.IO) {
        accountDao.get(uid)
    }

    suspend fun consume(uid: String) = withContext(Dispatchers.IO) {
        accountDao.consume(uid)
    }

    suspend fun addCredits(uid: String, delta: Int) = withContext(Dispatchers.IO) {
        accountDao.addCredits(uid, delta)
    }
}

/* ==================== 以下是你已有的歸一化/修正工具（保持不變） ==================== */

private fun clampPct(v: Int) = v.coerceIn(0, 100)
private fun clampList6(lst: List<Int>): List<Int> {
    val clamped = lst.map(::clampPct).take(6)
    return if (clamped.size < 6) clamped + List(6 - clamped.size) { 0 } else clamped
}
private fun avgPctOr(orig: Int, dims: List<Int>): Int {
    val cleaned = dims.map(::clampPct)
    val nonZeros = cleaned.count { it > 0 }
    return if (nonZeros > 0) cleaned.average().roundToInt().coerceIn(0, 100) else clampPct(orig)
}
private fun safeMul(a: Long, b: Long): Long = try {
    val r = a * b
    if (b != 0L && r / b != a) Long.MAX_VALUE else r
} catch (_: Throwable) { Long.MAX_VALUE }

private fun normalizeEpochMillis(raw: Long): Long {
    val now = System.currentTimeMillis()
    val lower = 946_684_800_000L
    val upper = now + 7L * 86_400_000L
    if (raw in lower..upper) return raw
    if (raw <= 0L) return now
    val candidates = listOf(raw, safeMul(raw, 1_000L), safeMul(raw, 60_000L), safeMul(raw, 3_600_000L), safeMul(raw, 86_400_000L))
    return candidates.firstOrNull { it in lower..upper } ?: now
}

private fun OptimizationRecord.normalizedForSave(): OptimizationRecord {
    val bDims = clampList6(dimsBefore)
    val aDims = clampList6(dimsAfter)
    return copy(
        beforeTotal = avgPctOr(beforeTotal, bDims),
        afterTotal = avgPctOr(afterTotal, aDims),
        dimsBefore = bDims,
        dimsAfter = aDims,
        createdAt = normalizeEpochMillis(createdAt)
    )
}

private fun OptimizationRecord.fixedForView(): OptimizationRecord = normalizedForSave()

/* ==================== UI <-> Entity 映射 ==================== */

internal fun OptimizationRecordEntity.toUi() = OptimizationRecord(
    id = id,
    userId = userId,
    resumeText = resumeText,
    jdText = jdText,
    optimizedText = optimizedText,
    beforeTotal = beforeTotal,
    afterTotal = afterTotal,
    dimsBefore = dimsBefore,
    dimsAfter = dimsAfter,
    createdAt = createdAt,
    analysis = null
)

internal fun OptimizationRecord.toEntity() = OptimizationRecordEntity(
    id = if (id == 0L) 0L else id,
    userId = userId,
    resumeText = resumeText,
    jdText = jdText,
    optimizedText = optimizedText,
    beforeTotal = beforeTotal,
    afterTotal = afterTotal,
    dimsBefore = dimsBefore,
    dimsAfter = dimsAfter,
    createdAt = createdAt
)
