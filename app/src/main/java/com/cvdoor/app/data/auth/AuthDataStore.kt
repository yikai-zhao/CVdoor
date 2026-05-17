package com.cvdoor.app.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore("cvats_auth")

class AuthDataStore(private val context: Context) {
    private object Keys {
        val UID  = stringPreferencesKey("uid")
        val NAME = stringPreferencesKey("name")
    }

    val uid: Flow<String?> = context.dataStore.data.map { it[Keys.UID] }
    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.NAME] }

    suspend fun getUidNow(): String? =
        context.dataStore.data.first()[Keys.UID]

    /** 確保有一個 UID：優先返回已有的，否則生成 guest-xxxx 並持久化 */
    suspend fun ensureGuest(): String {
        val existing = getUidNow()
        if (!existing.isNullOrBlank()) return existing
        val id = "guest-" + UUID.randomUUID().toString().take(8)
        set(id, "Guest User")
        return id
    }

    suspend fun set(uid: String, name: String) {
        context.dataStore.edit {
            it[Keys.UID] = uid
            it[Keys.NAME] = name
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
