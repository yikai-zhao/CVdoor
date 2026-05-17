package com.cvdoor.app.jobs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cvdoor.app.auth.AuthDataStore
import com.cvdoor.app.data.AppDatabase
import com.cvdoor.app.data.JobEntry
import com.cvdoor.app.data.JobStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class JobsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.jobEntryDao()
    private val auth = AuthDataStore(app)

    val uid: StateFlow<String> = auth.uid
        .map { it ?: "guest" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "guest")

    @OptIn(ExperimentalCoroutinesApi::class)
    val allJobs: StateFlow<List<JobEntry>> = uid.flatMapLatest { u ->
        dao.observeAll(u)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveJob(title: String, company: String, jdText: String, atsScore: Int = 0) {
        viewModelScope.launch {
            dao.insert(
                JobEntry(
                    userId = uid.value,
                    title = title.ifBlank { "Untitled Job" },
                    company = company,
                    jdText = jdText,
                    atsScore = atsScore
                )
            )
        }
    }

    fun updateStage(job: JobEntry, stage: JobStage) {
        viewModelScope.launch { dao.update(job.copy(stage = stage.name)) }
    }

    fun deleteJob(id: Long) {
        viewModelScope.launch { dao.deleteById(id) }
    }
}
