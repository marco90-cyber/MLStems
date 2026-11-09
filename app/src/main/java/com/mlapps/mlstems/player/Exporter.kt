package com.mlapps.mlstems.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.mlapps.mlstems.engine.AudioFormat
import com.mlapps.mlstems.engine.PcmWriter
import com.mlapps.mlstems.engine.Wav
import java.io.File
import java.io.RandomAccessFile

class ExportCancelled : Exception("Esportazione annullata")

/** Esporta in Download/MLStems (MediaStore, nessun permesso richiesto da Android 10). */
object Exporter {

    private fun create(ctx: Context, name: String, format: AudioFormat): Pair<Uri, java.io.OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.${format.ext}")
            put(MediaStore.Downloads.MIME_TYPE, format.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MLStems")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        return uri to ctx.contentResolver.openOutputStream(uri)!!
    }

    /** Apre il file di destinazione; se qualcosa va storto o l'utente annulla, il file a metà viene cancellato. */
    private inline fun withTarget(ctx: Context, name: String, format: AudioFormat, total: Long, body: (PcmWriter) -> Unit) {
        val (uri, out) = create(ctx, name, format)
        try {
            format.open(out, total).use(body)
        } catch (e: Throwable) {
            runCatching { out.close() }
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Converte un singolo stem (WAV in cache) nel formato scelto. */
    fun exportStem(
        ctx: Context, title: String, stemName: String, wav: File, format: AudioFormat,
        onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false },
    ) {
        RandomAccessFile(wav, "r").use { raf ->
            val total = (raf.length() - Wav.HEADER) / 4
            withTarget(ctx, "$title - $stemName", format, total) { w ->
                val block = 8192
                val bytes = ByteArray(block * 4)
                val l = ShortArray(block); val r = ShortArray(block)
                raf.seek(Wav.HEADER.toLong())
                var frame = 0L
                while (frame < total) {
                    if (cancelled()) throw ExportCancelled()
                    val n = minOf(block.toLong(), total - frame).toInt()
                    raf.readFully(bytes, 0, n * 4)
                    for (k in 0 until n) {
                        l[k] = ((bytes[4 * k + 1].toInt() shl 8) or (bytes[4 * k].toInt() and 0xFF)).toShort()
                        r[k] = ((bytes[4 * k + 3].toInt() shl 8) or (bytes[4 * k + 2].toInt() and 0xFF)).toShort()
                    }
                    w.write(l, r, n)
                    frame += n
                    onProgress(frame.toFloat() / total)
                }
            }
        }
    }

    /** Mix finale con i volumi / mute / solo correnti. */
    fun exportMix(
        ctx: Context, title: String, files: List<File>, gains: FloatArray, muted: BooleanArray, solo: BooleanArray,
        format: AudioFormat, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false },
    ) {
        val raf = files.map { RandomAccessFile(it, "r") }
        try {
            val total = raf.minOf { it.length() - Wav.HEADER } / 4
            withTarget(ctx, "$title - mix", format, total) { w ->
                val block = 4096
                val bytes = ByteArray(block * 4)
                val mix = FloatArray(block * 2)
                val pl = ShortArray(block)
                val pr = ShortArray(block)
                val anySolo = solo.any { it }
                var frame = 0L
                while (frame < total) {
                    if (cancelled()) throw ExportCancelled()
                    val frames = minOf(block.toLong(), total - frame).toInt()
                    mix.fill(0f)
                    for (i in files.indices) {
                        if (if (anySolo) !solo[i] else muted[i]) continue
                        raf[i].seek(Wav.HEADER + frame * 4)
                        raf[i].readFully(bytes, 0, frames * 4)
                        for (k in 0 until frames * 2) {
                            val s = (bytes[2 * k + 1].toInt() shl 8) or (bytes[2 * k].toInt() and 0xFF)
                            mix[k] += s * gains[i]
                        }
                    }
                    for (k in 0 until frames) {
                        pl[k] = mix[2 * k].coerceIn(-32768f, 32767f).toInt().toShort()
                        pr[k] = mix[2 * k + 1].coerceIn(-32768f, 32767f).toInt().toShort()
                    }
                    w.write(pl, pr, frames)
                    frame += frames
                    onProgress(frame.toFloat() / total)
                }
            }
        } finally {
            raf.forEach { it.close() }
        }
    }
}
