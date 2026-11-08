package com.mlapps.mlstems.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mlapps.mlstems.data.ModelManager
import com.mlapps.mlstems.data.Project
import com.mlapps.mlstems.data.ProjectRepository
import com.mlapps.mlstems.data.StemModel
import com.mlapps.mlstems.engine.DownloadService
import com.mlapps.mlstems.engine.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = ProjectRepository(app)
    val models = ModelManager(app)

    val projects = MutableStateFlow(repo.list())
    val installed = MutableStateFlow(models.installedIds())
    val downloads = DownloadState.downloads   // gestiti da DownloadService: continuano in background
    val message = MutableStateFlow<String?>(null)

    /** Cambia a ogni aggiornamento del catalogo / dei modelli, per far ridisegnare la lista. */
    val revision = MutableStateFlow(0)

    init {
        refreshCatalog()
        viewModelScope.launch {   // quando un download finisce (anche in background) aggiorna la lista
            DownloadState.tick.collect {
                installed.value = models.installedIds()
                revision.update { it + 1 }
            }
        }
        viewModelScope.launch {
            DownloadState.message.filterNotNull().collect { message.value = it; DownloadState.message.value = null }
        }
    }

    fun refreshProjects() { projects.value = repo.list() }

    fun refreshCatalog() = io {
        models.refreshCatalog()
        installed.value = models.installedIds()
        revision.update { it + 1 }
    }

    fun download(m: StemModel) = DownloadService.start(getApplication(), m.id)

    fun importModel(m: StemModel, uri: Uri) = io {
        try { models.importFrom(m, uri) } catch (e: Exception) { message.value = e.message }
        installed.value = models.installedIds()
        revision.update { it + 1 }
    }

    fun deleteModel(m: StemModel) = io {
        models.delete(m)
        installed.value = models.installedIds()
        revision.update { it + 1 }
    }

    fun deleteProject(p: Project) = io {
        repo.delete(p.id)
        refreshProjects()
    }

    fun saveProject(p: Project) = io { repo.save(p) }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
