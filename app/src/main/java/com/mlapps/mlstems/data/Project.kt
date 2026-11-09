package com.mlapps.mlstems.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

@Serializable
data class StemTrack(
    val name: String,
    val file: String,            // nome file dentro la cartella del progetto
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
)

@Serializable
data class Project(
    val id: String,
    val title: String,
    val sourceHash: String,      // SHA-256 del brano originale: evita di rielaborare lo stesso file
    val modelId: String,
    val stems: List<StemTrack>,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val decodeMs: Long = 0,      // tempo di lettura del brano
    val separateMs: Long = 0,    // tempo di separazione vero e proprio
    val accel: String = "CPU",   // "CPU" oppure "NPU"
    val folder: String = "",     // cartella in Download/MLStems con la copia dei file ("" se non c'è)
) {
    /** Quante volte più veloce del tempo reale (0 se non misurato). */
    val realtimeFactor: Float get() = if (separateMs > 0) durationMs.toFloat() / separateMs else 0f
}

class ImportCancelled : Exception("Caricamento annullato")

/**
 * I brani vivono nella cartella scelta dall'utente (di solito Download/MLStems): una sottocartella per brano
 * con project.json e un FLAC per ogni stem. Nessuna copia interna: reinstallando l'app basta indicare di nuovo la cartella.
 * L'id di un progetto è il nome della sua cartella.
 */
class ProjectRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("settings", 0)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val resolver get() = context.contentResolver

    private class Entry(val project: Project, val folder: DocumentFile)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    // ── cartella dei progetti ──

    private fun root(): DocumentFile? {
        val uri = prefs.getString("library_tree", null)?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.canWrite() }
    }

    /** Nome della cartella scelta, o null se non è stata scelta (o non è più accessibile). */
    fun libraryName(): String? = root()?.name

    fun setLibrary(tree: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.edit().putString("library_tree", tree.toString()).apply()
        cache.clear()
    }

    // ── lettura ──

    private fun readProject(folder: DocumentFile): Project? = runCatching {
        val meta = folder.findFile("project.json") ?: return null
        val p = json.decodeFromString<Project>(resolver.openInputStream(meta.uri)!!.use { it.readBytes().decodeToString() })
        val name = folder.name ?: return null
        p.copy(id = name, folder = name)
    }.getOrNull()

    fun list(): List<Project> {
        val r = root() ?: return emptyList()
        val found = r.listFiles().filter { it.isDirectory }.mapNotNull { f -> readProject(f)?.let { Entry(it, f) } }
        cache.clear()
        found.forEach { cache[it.project.id] = it }
        return found.map { it.project }.sortedByDescending { it.createdAt }
    }

    fun load(id: String): Project = (cache[id] ?: error("Progetto non trovato")).project

    /** URI di un file del progetto (null se manca). */
    fun fileUri(id: String, name: String): Uri? = cache[id]?.folder?.findFile(name)?.uri

    fun findByHash(hash: String, modelId: String, stemCount: Int = 0): Project? =
        list().firstOrNull { it.sourceHash == hash && it.modelId == modelId && (stemCount == 0 || it.stems.size == stemCount) }

    // ── scrittura ──

    private fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().trim('.').ifEmpty { "Brano" }

    /** Crea la cartella di un nuovo brano (nome unico). */
    fun newFolder(title: String): DocumentFile {
        val r = root() ?: error("Scegli prima la cartella dei progetti")
        val base = clean(title)
        var name = base
        var n = 2
        while (r.findFile(name) != null) name = "$base ($n)".also { n++ }
        return r.createDirectory(name) ?: error("Non riesco a creare la cartella")
    }

    /** Crea un file vuoto nella cartella e restituisce il documento (il nome finale può differire). */
    fun newFile(folder: DocumentFile, name: String, mime: String): DocumentFile =
        folder.createFile(mime, name) ?: error("Non riesco a creare $name")

    /** Scrive project.json e registra il progetto. */
    fun finish(folder: DocumentFile, project: Project): Project {
        val name = folder.name!!
        val p = project.copy(id = name, folder = name)
        writeMeta(folder, p)
        cache[name] = Entry(p, folder)
        return p
    }

    private fun writeMeta(folder: DocumentFile, p: Project) {
        val doc = folder.findFile("project.json") ?: folder.createFile("application/json", "project.json") ?: error("project.json")
        resolver.openOutputStream(doc.uri, "wt")!!.use { it.write(json.encodeToString(p).toByteArray()) }
    }

    /** Salva le modifiche (volumi, mute, solo). */
    fun save(project: Project) {
        val e = cache[project.id] ?: return
        writeMeta(e.folder, project)
        cache[project.id] = Entry(project, e.folder)
    }

    fun delete(id: String) {
        cache.remove(id)?.folder?.delete()
        com.mlapps.mlstems.engine.StemCache.clear(context, id)
    }

    /** Spazio occupato dai brani nella cartella dei progetti. */
    fun usedBytes(): Long = root()?.listFiles().orEmpty().filter { it.isDirectory }.sumOf { d -> d.listFiles().sumOf { it.length() } }

    fun deleteFolder(folder: DocumentFile) { runCatching { folder.delete() } }

    // ── file .mlstems ──

    /** Salva il progetto in un unico file (zip senza compressione: project.json + gli stem). */
    fun exportTo(id: String, out: java.io.OutputStream) {
        val folder = cache[id]?.folder ?: error("Progetto non trovato")
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out, 1 shl 16)).use { zip ->
            zip.setLevel(0)                       // FLAC è già compresso
            folder.listFiles().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry(f.name ?: return@forEach))
                resolver.openInputStream(f.uri)!!.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Legge un file progetto nella cartella dei progetti. Restituisce il progetto e se era già presente. */
    fun importFrom(
        input: java.io.InputStream, totalBytes: Long = -1, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): Pair<Project, Boolean> {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val buf = ByteArray(1 shl 16)
            var done = 0L
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(input)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = File(e.name).name          // niente percorsi: evita file fuori cartella
                    if (!e.isDirectory && name.isNotEmpty()) File(tmp, name).outputStream().use { o ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (cancelled()) throw ImportCancelled()
                            if (totalBytes > 0) onProgress((0.5f * done / totalBytes).coerceAtMost(0.5f))
                        }
                    }
                    e = zip.nextEntry
                }
            }
            val project = json.decodeFromString<Project>(File(tmp, "project.json").readText())
            require(project.stems.isNotEmpty() && project.stems.all { File(tmp, File(it.file).name).exists() }) { "File progetto incompleto" }
            findByHash(project.sourceHash, project.modelId)?.let { return it to true }
            return storeFrom(project, tmp, onProgress, cancelled) to false
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Copia i file di un progetto (da una cartella locale) nella cartella dei progetti. */
    private fun storeFrom(project: Project, src: File, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Project {
        val folder = newFolder(project.title)
        try {
            val stems = project.stems.mapIndexed { i, st ->
                val name = clean(st.name) + "." + File(st.file).extension
                val doc = newFile(folder, name, if (name.endsWith("flac")) "audio/flac" else "audio/x-wav")
                resolver.openOutputStream(doc.uri)!!.use { out ->
                    File(src, File(st.file).name).inputStream().use { inp ->
                        val b = ByteArray(1 shl 16)
                        while (true) {
                            val n = inp.read(b)
                            if (n < 0) break
                            out.write(b, 0, n)
                            if (cancelled()) throw ImportCancelled()
                        }
                    }
                }
                onProgress(0.5f + 0.5f * (i + 1) / project.stems.size)
                st.copy(file = doc.name ?: name)
            }
            return finish(folder, project.copy(stems = stems))
        } catch (e: Throwable) {
            folder.delete(); throw e
        }
    }

    /** Porta nella cartella dei progetti i brani che le versioni precedenti tenevano dentro l'app. */
    fun pendingInternal(): Int = File(context.filesDir, "projects").listFiles().orEmpty().count { it.isDirectory }

    fun migrateInternal(onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Int {
        if (root() == null) return 0
        val dirs = File(context.filesDir, "projects").listFiles().orEmpty().filter { it.isDirectory }
        var moved = 0
        for ((n, dir) in dirs.withIndex()) {
            try {
                val p = json.decodeFromString<Project>(File(dir, "project.json").readText())
                if (findByHash(p.sourceHash, p.modelId) == null) {
                    storeFrom(p, dir, { f -> onProgress((n + f) / dirs.size) }, cancelled); moved++
                }
                dir.deleteRecursively()
            } catch (e: ImportCancelled) {
                break                       // quelli rimasti si spostano al prossimo avvio
            } catch (e: Exception) {
                // progetto illeggibile: resta dov'è
            }
            onProgress((n + 1f) / dirs.size)
        }
        return moved
    }
}
