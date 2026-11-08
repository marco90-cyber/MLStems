package com.mlapps.mlstems.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Gestisce i file .onnx dei modelli: download dentro l'app, import da file, eliminazione. */
class ModelManager(private val context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun file(model: StemModel) = File(dir, model.fileName)

    fun isInstalled(model: StemModel) = file(model).let { it.exists() && it.length() > 0 }

    fun installedIds(): Set<String> =
        ModelCatalog.all.filter { isInstalled(it) }.map { it.id }.toSet()

    fun download(model: StemModel, onProgress: (Float) -> Unit) {
        check(model.url.isNotBlank()) { "URL del modello non configurata: importa il file .onnx a mano" }
        val part = File(dir, model.fileName + ".part")
        val conn = URL(model.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.connect()
        check(conn.responseCode in 200..299) { "Download fallito (HTTP ${conn.responseCode})" }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        check(part.renameTo(file(model))) { "Impossibile salvare il modello" }
    }

    fun importFrom(model: StemModel, uri: Uri) {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            file(model).outputStream().use { input.copyTo(it) }
        }
    }

    fun delete(model: StemModel) {
        file(model).delete()
    }
}
