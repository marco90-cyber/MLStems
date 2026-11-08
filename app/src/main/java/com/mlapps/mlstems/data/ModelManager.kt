package com.mlapps.mlstems.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
data class RemoteFile(
    val name: String,
    val sha256: String = "",
    val size: Long = 0,
    val urls: List<String> = emptyList(),   // provati in ordine: Hugging Face, poi copia nelle Releases
)

@Serializable
data class RemoteModel(val id: String, val version: Int = 1, val files: List<RemoteFile> = emptyList())

@Serializable
data class RemoteCatalog(val models: List<RemoteModel> = emptyList())

/**
 * Gestisce i modelli: elenco file e versioni da models.json (nel repository), download dentro l'app
 * con riserva su più indirizzi, verifica SHA-256, avviso di aggiornamento, import da file.
 */
class ModelManager(private val context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val cache = File(context.filesDir, "models.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var catalog: Map<String, RemoteModel> = loadCached()

    // ───── catalogo remoto ─────

    private fun parse(text: String) =
        json.decodeFromString<RemoteCatalog>(text).models.associateBy { it.id }

    private fun loadCached(): Map<String, RemoteModel> =
        runCatching { if (cache.exists()) parse(cache.readText()) else emptyMap() }.getOrDefault(emptyMap())

    /** Scarica models.json aggiornato; se manca la rete resta quello in cache. */
    fun refreshCatalog(): Boolean = runCatching {
        val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        catalog = parse(text)
        cache.writeText(text)
        true
    }.getOrDefault(false)

    fun remote(model: StemModel): RemoteModel? = catalog[model.id]

    /** True se esiste un indirizzo da cui scaricare il modello. */
    fun isDownloadable(model: StemModel) =
        remote(model)?.files?.let { f -> f.isNotEmpty() && f.all { it.urls.isNotEmpty() } } == true

    // ───── stato installazione ─────

    val modelsDir: File get() = dir

    fun file(model: StemModel) = File(dir, model.fileName)

    /** File .json con i parametri del modello (solo famiglia Demucs). */
    fun configFile(model: StemModel): File =
        if (model.fileName.endsWith(".json")) file(model) else File(dir, model.fileName.substringBeforeLast('.') + ".json")

    private fun versionFile(model: StemModel) = File(dir, model.fileName + ".version")

    private fun requiredFiles(model: StemModel): List<File> =
        remote(model)?.files?.takeIf { it.isNotEmpty() }?.map { File(dir, it.name) } ?: listOf(file(model))

    fun isInstalled(model: StemModel) =
        file(model).let { it.exists() && it.length() > 0 } &&
            requiredFiles(model).all { it.exists() && it.length() > 0 }

    fun installedIds(): Set<String> =
        ModelCatalog.all.filter { isInstalled(it) }.map { it.id }.toSet()

    /** Aggiornamento disponibile solo per i modelli scaricati dall'app (hanno il file versione). */
    fun updateAvailable(model: StemModel): Boolean {
        val installed = versionFile(model).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return false
        return (remote(model)?.version ?: 0) > installed
    }

    // ───── download ─────

    fun download(model: StemModel, onProgress: (Float) -> Unit) {
        val spec = remote(model)
        check(spec != null && isDownloadable(model)) { "Modello non ancora disponibile per il download: importa il file a mano" }
        val total = spec.files.sumOf { it.size }.coerceAtLeast(1L)
        var doneBefore = 0L
        for (f in spec.files) {
            val target = File(dir, f.name)
            if (!(target.exists() && f.sha256.isNotBlank() && sha256(target) == f.sha256)) {
                fetch(f, target) { read -> onProgress(((doneBefore + read).toFloat() / total).coerceIn(0f, 1f)) }
            }
            doneBefore += f.size
        }
        versionFile(model).writeText(spec.version.toString())
    }

    /** Prova ogni indirizzo in ordine; il primo che scarica e supera il controllo SHA-256 vince. */
    private fun fetch(f: RemoteFile, target: File, onBytes: (Long) -> Unit) {
        val errors = mutableListOf<String>()
        for (url in f.urls) {
            val part = File(dir, f.name + ".part")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.connect()
                check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                val md = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            onBytes(done)
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                check(f.sha256.isBlank() || hex == f.sha256) { "checksum diverso" }
                target.delete()
                check(part.renameTo(target)) { "impossibile salvare il file" }
                return
            } catch (e: Exception) {
                part.delete()
                errors += "${URL(url).host}: ${e.message}"
            }
        }
        error("Download di ${f.name} non riuscito (${errors.joinToString("; ")})")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ───── import / eliminazione ─────

    fun importFrom(model: StemModel, uri: Uri) {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            file(model).outputStream().use { input.copyTo(it) }
        }
        versionFile(model).delete()   // importato a mano: nessun controllo aggiornamenti
    }

    fun delete(model: StemModel) {
        requiredFiles(model).forEach { it.delete() }
        file(model).delete()
        versionFile(model).delete()
    }

    companion object {
        const val CATALOG_URL = "https://raw.githubusercontent.com/marco90-cyber/MLStems/main/models.json"
    }
}
