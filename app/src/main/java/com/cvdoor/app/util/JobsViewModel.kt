package com.cvdoor.app.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cvdoor.app.data.AppDatabase
import com.cvdoor.app.data.ApplicationStage
import com.cvdoor.app.data.JobApplicationEntity
import com.cvdoor.app.data.SavedJobEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class JobsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val savedJobDao = db.savedJobDao()
    private val appDao = db.jobApplicationDao()

    val savedJobs: StateFlow<List<SavedJobEntity>> =
        savedJobDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentUserId = MutableStateFlow<String?>(null)

    val applications: StateFlow<List<JobApplicationEntity>> =
        _currentUserId
            .flatMapLatest { uid ->
                if (uid.isNullOrBlank()) flowOf(emptyList())
                else appDao.observeAll(uid)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setUserId(uid: String?) {
        _currentUserId.value = uid
    }

    fun saveJob(title: String, company: String, jdText: String, atsScore: Int) =
        viewModelScope.launch {
            savedJobDao.insert(SavedJobEntity(title = title, company = company, jdText = jdText, atsScore = atsScore))
        }

    fun deleteJob(job: SavedJobEntity) = viewModelScope.launch { savedJobDao.delete(job) }

    fun addApplication(uid: String, title: String, company: String, atsScore: Int) =
        viewModelScope.launch {
            appDao.insert(
                JobApplicationEntity(
                    userId = uid,
                    title = title,
                    company = company,
                    atsScore = atsScore
                )
            )
        }

    fun updateStage(app: JobApplicationEntity, newStage: String) = viewModelScope.launch {
        appDao.update(app.copy(stage = newStage))
    }

    fun deleteApplication(app: JobApplicationEntity) = viewModelScope.launch {
        appDao.deleteById(app.id)
    }
}
