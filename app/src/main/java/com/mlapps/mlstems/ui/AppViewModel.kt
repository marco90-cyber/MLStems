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

    val projects = MutableStateFlow<List<Project>>(emptyList())
    /** Nome della cartella dei progetti scelta, null se non ancora scelta. */
    val library = MutableStateFlow(repo.libraryName())
    val installed = MutableStateFlow(models.installedIds())
    val downloads = DownloadState.downloads   // gestiti da DownloadService: continuano in background
    val message = MutableStateFlow<String?>(null)

    /** Cambia a ogni aggiornamento del catalogo / dei modelli, per far ridisegnare la lista. */
    val revision = MutableStateFlow(0)

    init {
        refreshCatalog()
        if (library.value != null) io { repo.migrateInternal(); projects.value = repo.list() }
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

    data class Loading(val label: String, val progress: Float)

    /** Caricamento di un file progetto in corso (null se nessuno). */
    val loading = MutableStateFlow<Loading?>(null)
    private val cancelLoad = java.util.concurrent.atomic.AtomicBoolean(false)

    fun loadProject(uri: Uri) {
        if (loading.value != null) return
        cancelLoad.set(false)
        loading.value = Loading("Apro il file…", 0f)
        io {
            val app = getApplication<Application>()
            val msg = try {
                val size = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
                val (p, existed) = app.contentResolver.openInputStream(uri)!!.use {
                    repo.importFrom(it, size, { f -> loading.value = Loading("Carico il progetto", f) }, { cancelLoad.get() })
                }
                if (existed) "“${p.title}” era già presente" else "Progetto “${p.title}” caricato"
            } catch (e: com.mlapps.mlstems.data.ImportCancelled) {
                "Caricamento annullato"
            } catch (e: Exception) {
                "File progetto non valido"
            }
            loading.value = null
            projects.value = repo.list()
            message.value = msg
        }
    }

    fun cancelLoading() = cancelLoad.set(true)

    fun refreshProjects() = io { projects.value = repo.list() }

    fun setLibrary(tree: Uri) = io {
        repo.setLibrary(tree)
        library.value = repo.libraryName()
        val moved = repo.migrateInternal()
        projects.value = repo.list()
        message.value = if (moved > 0) "Cartella scelta: spostati $moved brani dall'app" else "Cartella dei progetti: ${library.value}"
    }

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
        projects.value = repo.list()
    }

    fun saveProject(p: Project) = io { repo.save(p) }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
