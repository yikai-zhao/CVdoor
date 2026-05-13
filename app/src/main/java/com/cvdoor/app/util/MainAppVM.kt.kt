// File: app/src/main/java/com/cvdoor/app/util/MainAppVM.kt
package com.cvdoor.app.util

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cvdoor.app.api.ApiService
import com.cvdoor.app.api.OptimizeReq
import com.cvdoor.app.api.AnalysisDTO
import com.cvdoor.app.auth.AuthDataStore
import com.cvdoor.app.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.HttpException

class MainAppVM(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)
    private val api by lazy { ApiService.create() }
    private val auth = AuthDataStore(app)

    val uid: StateFlow<String?> =
        auth.uid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val displayName: StateFlow<String?> =
        auth.displayName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _history = MutableStateFlow<List<OptimizationRecord>>(emptyList())
    val history: StateFlow<List<OptimizationRecord>> = _history

    private val _credits = MutableStateFlow(0)
    val credits: StateFlow<Int> = _credits

    // 游客 credits（本地）
    private var guestCredits = 0

    // 本地删除的记录 ID
    private val locallyDeletedIds = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            uid.collectLatest { u ->
                locallyDeletedIds.clear()
                if (u.isNullOrBlank()) {
                    _history.value = emptyList()
                    _credits.value = guestCredits
                } else {
                    repo.ensureAccount(u)
                    repo.getAccount(u)?.let { _credits.value = it.remainingCredits }
                    refreshHistory(u)
                }
            }
        }
    }

    /* ---------- Convert AnalysisDTO → AnalysisOut ---------- */
    private fun mapAnalysis(dto: AnalysisDTO?): AnalysisOut? {
        if (dto == null) return null
        return AnalysisOut(
            overall = dto.overall?.let {
                OverallAnalysis(
                    summary = it.summary,
                    strengths = it.strengths ?: emptyList(),
                    issues = it.issues ?: emptyList(),
                    actions = it.actions ?: emptyList()
                )
            },
            dimensions = dto.dimensions?.map { d ->
                DimAnalysis(
                    name = d.name,
                    before = d.before,
                    after = d.after,
                    reasons = d.reasons ?: emptyList(),
                    problems = d.problems ?: emptyList(),
                    suggestions = d.suggestions ?: emptyList(),
                    missingBefore = d.missingBefore ?: emptyList(),
                    addedAfter = d.addedAfter ?: emptyList()
                )
            } ?: emptyList()
        )
    }

    /* ---------- Merge remote + 保留本地删除 ---------- */
    private fun mergeRemote(remote: List<OptimizationRecord>): List<OptimizationRecord> {
        val local = _history.value
        val byId = remote.associateBy { it.id }.toMutableMap()

        // 补充 analysis
        local.forEach { l ->
            val r = byId[l.id]
            if (r != null && r.analysis == null && l.analysis != null) {
                byId[l.id] = r.copy(analysis = l.analysis)
            }
        }

        // 去掉本地已删除的
        locallyDeletedIds.forEach { delId -> byId.remove(delId) }

        return byId.values.sortedByDescending { it.createdAt }
    }

    /* ---------- 手动刷新 ---------- */
    suspend fun refreshHistory(u: String) {
        try {
            val remote = api.listRecords(userId = u, limit = 100).map { r ->
                OptimizationRecord(
                    id = r.id,
                    userId = r.userId,
                    resumeText = r.resumeText,
                    jdText = r.jdText,
                    optimizedText = r.optimizedText,
                    beforeTotal = r.beforeTotal,
                    afterTotal = r.afterTotal,
                    dimsBefore = r.dimsBefore,
                    dimsAfter = r.dimsAfter,
                    createdAt = r.createdAtSec * 1000L,
                    analysis = mapAnalysis(r.analysis)
                )
            }
            _history.value = mergeRemote(remote)
        } catch (e: Exception) {
            Log.e("MainAppVM", "Failed to refresh history", e)
        }
    }

    /* ---------- Credits ---------- */
    fun addCredits(n: Int) = viewModelScope.launch {
        val u = uid.value
        if (u.isNullOrBlank()) {
            guestCredits += n
            _credits.value = guestCredits
        } else {
            repo.addCredits(u, n)
            _credits.value = repo.getAccount(u)?.remainingCredits ?: 0
        }
    }

    private suspend fun consumeOne(): Boolean {
        val u = uid.value
        return if (u.isNullOrBlank()) {
            if (guestCredits <= 0) return false
            guestCredits--
            _credits.value = guestCredits
            true
        } else {
            val acc = repo.getAccount(u) ?: return false
            if (acc.remainingCredits <= 0) return false
            repo.consume(u)
            _credits.value = repo.getAccount(u)?.remainingCredits ?: 0
            true
        }
    }

    fun logout() = viewModelScope.launch { auth.clear() }

    /* ---------- Optimize ---------- */
    fun optimizeAndSave(
        resumeText: String,
        jdText: String,
        onDone: (OptimizationRecord) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val u = uid.value
                if (u.isNullOrBlank()) {
                    throw IllegalStateException("必须登录才能保存记录")
                }
                if (!consumeOne()) throw IllegalStateException("No credits remaining")

                val resp = api.optimize(
                    OptimizeReq(
                        resumeText = resumeText,
                        jdText = jdText,
                        userId = u
                    )
                )

                val dimsBefore = resp.dimsBefore ?: resp.beforeScores ?: emptyList()
                val dimsAfter = resp.dimsAfter ?: resp.afterScores ?: emptyList()
                val analysis = mapAnalysis(resp.analysis)

                val rec = OptimizationRecord(
                    id = resp.recordId ?: System.currentTimeMillis(),
                    userId = u,
                    resumeText = resumeText,
                    jdText = jdText,
                    optimizedText = resp.optimized,
                    beforeTotal = resp.beforeTotal,
                    afterTotal = resp.afterTotal,
                    dimsBefore = dimsBefore,
                    dimsAfter = dimsAfter,
                    createdAt = (resp.createdAtSec ?: (System.currentTimeMillis() / 1000)) * 1000L,
                    analysis = analysis
                )

                _history.value = listOf(rec) + _history.value
                onDone(rec)

            } catch (t: Throwable) {
                val friendly = when (t) {
                    is java.net.SocketTimeoutException -> "Network timeout, please try again"
                    is java.net.UnknownHostException -> "Network unavailable"
                    is IllegalStateException -> t.message ?: "Request failed"
                    else -> t.message ?: "Request failed"
                }
                onError(Exception(friendly, t))
            }
        }
    }

    /* ---------- Delete one record ---------- */
    fun deleteRecord(recordId: Long) = viewModelScope.launch {
        val u = uid.value ?: return@launch
        val before = _history.value
        _history.value = before.filterNot { it.id == recordId }
        locallyDeletedIds.add(recordId)
        try {
            val resp = api.deleteRecord(recordId, userId = u)
            if (resp.isSuccessful) {
                Log.d("MainAppVM", "deleteRecord: success id=$recordId")
            } else {
                Log.e("MainAppVM", "deleteRecord failed code=${resp.code()}")
                _history.value = before
                locallyDeletedIds.remove(recordId)
            }
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 404) {
                Log.w("MainAppVM", "Record not found on server, keep local deletion")
            } else {
                Log.e("MainAppVM", "Failed to delete record id=$recordId", e)
                _history.value = before
                locallyDeletedIds.remove(recordId)
            }
        }
    }

    /* ---------- Clear all history ---------- */
    fun clearHistory() = viewModelScope.launch {
        val u = uid.value ?: return@launch
        val backup = _history.value
        _history.value = emptyList()
        locallyDeletedIds.addAll(backup.map { it.id })
        try {
            val resp = api.clearRecords(u)
            if (!resp.isSuccessful) {
                Log.e("MainAppVM", "clearHistory failed code=${resp.code()}")
                _history.value = backup
                locallyDeletedIds.removeAll(backup.map { it.id })
            }
        } catch (e: Exception) {
            Log.e("MainAppVM", "Failed to clear history", e)
            _history.value = backup
            locallyDeletedIds.removeAll(backup.map { it.id })
        }
    }

    /* ---------- Prefill ---------- */
    private val _optimizePrefill = MutableStateFlow<Pair<String, String>?>(null)
    fun setOptimizePrefill(resume: String, jd: String) { _optimizePrefill.value = resume to jd }
    fun takeOptimizePrefill(): Pair<String, String>? = _optimizePrefill.getAndNull()

    private fun <T> StateFlow<T?>.getAndNull(): T? {
        val v = this.value
        if (this is MutableStateFlow<T?>) this.value = null
        return v
    }
}
