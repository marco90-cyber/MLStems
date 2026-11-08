package com.mlapps.mlstems.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mlapps.mlstems.data.ModelManager
import com.mlapps.mlstems.data.Project
import com.mlapps.mlstems.data.ProjectRepository
import com.mlapps.mlstems.data.StemModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = ProjectRepository(app)
    val models = ModelManager(app)

    val projects = MutableStateFlow(repo.list())
    val installed = MutableStateFlow(models.installedIds())
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val message = MutableStateFlow<String?>(null)

    fun refreshProjects() { projects.value = repo.list() }

    fun download(m: StemModel) = io {
        try {
            models.download(m) { p -> downloads.update { it + (m.id to p) } }
        } catch (e: Exception) {
            message.value = e.message
        } finally {
            downloads.update { it - m.id }
            installed.value = models.installedIds()
        }
    }

    fun importModel(m: StemModel, uri: Uri) = io {
        try { models.importFrom(m, uri) } catch (e: Exception) { message.value = e.message }
        installed.value = models.installedIds()
    }

    fun deleteModel(m: StemModel) = io {
        models.delete(m)
        installed.value = models.installedIds()
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
