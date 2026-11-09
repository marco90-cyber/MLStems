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
import kotlinx.coroutines.awaitAll
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
        if (library.value != null) migrate()
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

    fun setLibrary(tree: Uri) {
        repo.setLibrary(tree)
        library.value = repo.libraryName()
        migrate(announce = true)
    }

    /** Sposta nella cartella dei progetti i brani rimasti dentro l'app, con barra e Annulla. */
    private fun migrate(announce: Boolean = false) = io {
        var moved = 0
        if (repo.pendingInternal() > 0 && loading.value == null) {
            cancelLoad.set(false)
            loading.value = Loading("Sposto i brani nella cartella", 0f)
            moved = repo.migrateInternal({ f -> loading.value = Loading("Sposto i brani nella cartella", f) }, { cancelLoad.get() })
            loading.value = null
        }
        projects.value = repo.list()
        if (moved > 0) message.value = "Spostati $moved brani dall'app alla cartella"
        else if (announce) message.value = "Cartella dei progetti: ${library.value}"
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

    // ── preparazione degli stem per l'ascolto: continua anche se esci dal mixer ──
    data class Prep(val projectId: String, val title: String, val progress: Float)
    class Prepared(val files: List<java.io.File>, val uris: List<Uri>)

    val preparing = MutableStateFlow<Prep?>(null)
    val prepared = MutableStateFlow<Map<String, Prepared>>(emptyMap())
    val prepFailed = MutableStateFlow<Set<String>>(emptySet())
    @Volatile var watching: String? = null          // brano di cui l'utente guarda il mixer
    private var prepJob: kotlinx.coroutines.Job? = null

    fun prepare(projectId: String) {
        if (prepared.value.containsKey(projectId) || preparing.value?.projectId == projectId) return
        prepJob?.cancel()
        val app = getApplication<Application>()
        val project = runCatching { repo.load(projectId) }.getOrNull() ?: run { prepFailed.update { it + projectId }; return }
        prepFailed.update { it - projectId }
        preparing.value = Prep(projectId, project.title, 0f)
        prepJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val u = project.stems.map { repo.fileUri(projectId, it.file) ?: error("manca ${it.file}") }
                val t0 = System.currentTimeMillis()
                val part = FloatArray(u.size)
                // più stem insieme: la decodifica di ognuno è indipendente
                val w: List<java.io.File> = kotlinx.coroutines.coroutineScope {
                    val jobs: List<kotlinx.coroutines.Deferred<java.io.File>> = u.mapIndexed { i, uri ->
                        kotlinx.coroutines.async(Dispatchers.IO) {
                            com.mlapps.mlstems.engine.StemCache.wavFor(app, projectId, uri, project.stems[i].file) { f: Float ->
                                part[i] = f
                                preparing.value = Prep(projectId, project.title, part.sum() / u.size)
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                val ms = System.currentTimeMillis() - t0
                if (ms > 1500) com.mlapps.mlstems.engine.ProcessingLog.add(app, "Preparazione mixer: ${u.size} stem in $ms ms")
                prepared.update { it + (projectId to Prepared(w, u)) }
                if (watching != projectId) message.value = "“${project.title}” è pronto per l'ascolto"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                prepFailed.update { it + projectId }
            } finally {
                if (preparing.value?.projectId == projectId) preparing.value = null
            }
        }
    }

    fun cancelPrepare() { prepJob?.cancel(); preparing.value = null }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
