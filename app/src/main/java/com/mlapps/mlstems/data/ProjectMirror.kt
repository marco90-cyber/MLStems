package com.mlapps.mlstems.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Copia di ogni brano in Download/MLStems/<brano>/: gli stem in FLAC con il loro nome (Voce.flac, ...)
 * e project.json per il ripristino dopo una reinstallazione.
 */
object ProjectMirror {
    private const val ROOT = "Download/MLStems"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val uri get() = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private fun clean(name: String) = name.replace(Regex("[\\/:*?\"<>|]"), " ").trim().trim('.').ifEmpty { "Brano" }

    private fun exists(ctx: Context, folder: String): Boolean =
        ctx.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("$ROOT/$folder/"), null,
        )?.use { it.count > 0 } ?: false

    /** Scrive la copia e restituisce il nome della cartella creata. */
    fun write(ctx: Context, p: Project, srcDir: File): String {
        val base = clean(p.title)
        var folder = base
        var n = 2
        while (exists(ctx, folder)) folder = "$base ($n)".also { n++ }
        val rel = "$ROOT/$folder/"

        val stems = p.stems.map { it.copy(file = clean(it.name) + "." + File(it.file).extension) }
        p.stems.forEachIndexed { i, st ->
            put(ctx, rel, stems[i].file, if (stems[i].file.endsWith("flac")) "audio/flac" else "audio/x-wav") { out ->
                File(srcDir, st.file).inputStream().use { it.copyTo(out) }
            }
        }
        val meta = json.encodeToString(p.copy(stems = stems, folder = folder))
        put(ctx, rel, "project.json", "application/json") { it.write(meta.toByteArray()) }
        return folder
    }

    private fun put(ctx: Context, rel: String, name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val u = ctx.contentResolver.insert(uri, values) ?: error("salvataggio non riuscito")
        ctx.contentResolver.openOutputStream(u)!!.use(body)
    }

    /** Cancella i file della cartella (solo quelli creati da questa installazione). Restituisce quanti. */
    fun delete(ctx: Context, folder: String): Int =
        ctx.contentResolver.delete(uri, "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf("$ROOT/$folder/"))
}
