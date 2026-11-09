package com.mlapps.mlstems.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

/** Ogni progetto è una cartella: project.json + un FLAC per ogni stem. */
class ProjectRepository(private val context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun list(): List<Project> =
        root.listFiles().orEmpty()
            .mapNotNull { runCatching { load(it.name) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun load(id: String): Project =
        json.decodeFromString(File(dir(id), "project.json").readText())

    fun save(project: Project) {
        File(dir(project.id), "project.json").writeText(json.encodeToString(project))
    }

    fun findByHash(hash: String, modelId: String): Project? =
        list().firstOrNull { it.sourceHash == hash && it.modelId == modelId }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        com.mlapps.mlstems.engine.StemCache.clear(context, id)
    }

    /** Salva il progetto in un unico file (zip senza compressione: project.json + gli stem). */
    fun exportTo(id: String, out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out, 1 shl 16)).use { zip ->
            zip.setLevel(0)                       // FLAC è già compresso
            dir(id).listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Legge un file progetto. Restituisce il progetto e se era già presente (stesso brano e modello). */
    fun importFrom(input: java.io.InputStream): Pair<Project, Boolean> {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
        try {
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(input)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = File(e.name).name          // niente percorsi: evita file fuori cartella
                    if (!e.isDirectory && name.isNotEmpty()) File(tmp, name).outputStream().use { zip.copyTo(it) }
                    e = zip.nextEntry
                }
            }
            val project = json.decodeFromString<Project>(File(tmp, "project.json").readText())
            require(project.stems.isNotEmpty() && project.stems.all { File(tmp, File(it.file).name).exists() }) { "File progetto incompleto" }
            findByHash(project.sourceHash, project.modelId)?.let { return it to true }
            val id = java.util.UUID.randomUUID().toString()
            val target = dir(id)
            tmp.listFiles().orEmpty().forEach { it.copyTo(File(target, it.name), overwrite = true) }
            val saved = project.copy(id = id, stems = project.stems.map { it.copy(file = File(it.file).name) })
            save(saved)
            return saved to false
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Ripristino: legge le cartelle di Download/MLStems scelte dall'utente (una cartella-brano oppure la cartella
     * che le contiene). Salta i brani già presenti. Restituisce (caricati, già presenti).
     */
    fun restoreFromTree(tree: android.net.Uri): Pair<Int, Int> {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree) ?: return 0 to 0
        val candidates = if (root.findFile("project.json") != null) listOf(root)
        else root.listFiles().filter { it.isDirectory }
        var added = 0; var skipped = 0
        for (doc in candidates) {
            val ok = runCatching {
                val meta = doc.findFile("project.json") ?: return@runCatching false
                val project = json.decodeFromString<Project>(
                    context.contentResolver.openInputStream(meta.uri)!!.use { it.readBytes().decodeToString() })
                if (findByHash(project.sourceHash, project.modelId) != null) { skipped++; return@runCatching true }
                val files = project.stems.map { doc.findFile(File(it.file).name) ?: error("manca ${it.file}") }
                val id = java.util.UUID.randomUUID().toString()
                val target = dir(id)
                try {
                    project.stems.forEachIndexed { i, st ->
                        context.contentResolver.openInputStream(files[i].uri)!!.use { inp ->
                            File(target, File(st.file).name).outputStream().use { inp.copyTo(it) }
                        }
                    }
                    save(project.copy(id = id, folder = doc.name ?: "", stems = project.stems.map { it.copy(file = File(it.file).name) }))
                } catch (e: Throwable) { delete(id); throw e }
                added++
                true
            }.getOrDefault(false)
            if (!ok) continue
        }
        return added to skipped
    }
}
