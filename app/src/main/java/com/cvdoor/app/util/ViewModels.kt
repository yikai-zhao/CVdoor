// File: ViewModels.kt
package com.cvdoor.app.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.cvdoor.app.api.ApiService
import com.cvdoor.app.api.OptimizeReq
import com.cvdoor.app.auth.AuthDataStore
import com.cvdoor.app.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AppVM(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val api by lazy { ApiService.create() }
    private val auth = AuthDataStore(app)

    // 登录信息
    val uid: StateFlow<String?> =
        auth.uid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val displayName: StateFlow<String?> =
        auth.displayName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 历史与次数
    private val _history = MutableStateFlow<List<OptimizationRecord>>(emptyList())
    val history: StateFlow<List<OptimizationRecord>> = _history

    private val _credits = MutableStateFlow(0)
    val credits: StateFlow<Int> = _credits

    init {
        viewModelScope.launch {
            uid.collect { u ->
                if (u.isNullOrBlank()) {
                    _history.value = emptyList()
                    _credits.value = 0
                } else {
                    repo.ensureAccount(u)
                    repo.getAccount(u)?.let { _credits.value = it.remainingCredits }
                    // ✅ 持续观察数据库里的记录
                    repo.observeHistory(u).collect { _history.value = it }
                }
            }
        }
    }

    fun addCredits(n: Int) = viewModelScope.launch {
        val u = uid.value ?: return@launch
        repo.addCredits(u, n)
        _credits.value = repo.getAccount(u)?.remainingCredits ?: 0
    }

    private suspend fun consumeOne(): Boolean {
        val u = uid.value ?: return false
        val acc = repo.getAccount(u) ?: return false
        if (acc.remainingCredits <= 0) return false
        repo.consume(u)
        _credits.value = repo.getAccount(u)?.remainingCredits ?: 0
        return true
    }

    /** 真·登出：退出 Google + 撤销授权 + 清空本地状态 */
    fun logout() = viewModelScope.launch {
        try {
            val gso = GoogleSignInOptions
                .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(getApplication(), gso)
            client.signOut()
            client.revokeAccess()
        } catch (_: Exception) { }
        auth.clear()
        _history.value = emptyList()
        _credits.value = 0
    }

    fun optimizeAndSave(
        resumeText: String,
        jdText: String,
        onDone: (OptimizationRecord) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val u = uid.value ?: throw IllegalStateException("尚未登录")
                if (!consumeOne()) throw IllegalStateException("剩余次数不足")

                val resp = api.optimize(OptimizeReq(resumeText, jdText, userId = u))
                val dimsBefore = resp.dimsBefore ?: resp.beforeScores ?: emptyList()
                val dimsAfter  = resp.dimsAfter  ?: resp.afterScores  ?: emptyList()

                val rec = OptimizationRecord(
                    id = 0L,
                    userId = u,
                    resumeText = resumeText,
                    jdText = jdText,
                    optimizedText = resp.optimized,
                    beforeTotal = resp.beforeTotal,
                    afterTotal = resp.afterTotal,
                    dimsBefore = dimsBefore,
                    dimsAfter = dimsAfter,
                    createdAt = System.currentTimeMillis()
                )

                val id = repo.saveRecord(rec)
                // ❌ 不再手动改 _history
                onDone(rec.copy(id = id))
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /** ✅ 删除单条记录（依赖 Room Flow 自动更新） */
    fun deleteRecord(recordId: Long) = viewModelScope.launch {
        val u = uid.value ?: return@launch
        repo.deleteRecord(u, recordId)
        // ❌ 不再手动改 _history，等待 Room 推送新数据
    }

    /** ✅ 清空历史（依赖 Room Flow 自动更新） */
    fun clearHistory() = viewModelScope.launch {
        val u = uid.value ?: return@launch
        repo.clearHistory(u)
        // ❌ 不再手动改 _history
    }
}
