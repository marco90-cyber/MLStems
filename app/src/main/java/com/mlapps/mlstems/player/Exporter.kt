package com.mlapps.mlstems.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.mlapps.mlstems.core.FlacEncoder
import com.mlapps.mlstems.engine.SAMPLE_RATE
import com.mlapps.mlstems.engine.Wav
import java.io.File
import java.io.RandomAccessFile

/** Esporta in Download/MLStems (MediaStore, nessun permesso richiesto da Android 10). */
object Exporter {

    private fun create(ctx: Context, name: String, ext: String = "flac"): Pair<Uri, java.io.OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Downloads.MIME_TYPE, if (ext == "wav") "audio/x-wav" else "audio/flac")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MLStems")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        return uri to ctx.contentResolver.openOutputStream(uri)!!
    }

    fun exportStem(ctx: Context, title: String, stemName: String, src: android.net.Uri, ext: String) {
        val (_, out) = create(ctx, "$title - $stemName", ext.lowercase())
        out.use { o -> ctx.contentResolver.openInputStream(src)!!.use { it.copyTo(o) } }
    }

    /** Mix finale con i volumi / mute / solo correnti. */
    fun exportMix(ctx: Context, title: String, files: List<File>, gains: FloatArray, muted: BooleanArray, solo: BooleanArray) {
        val raf = files.map { RandomAccessFile(it, "r") }
        try {
            val total = (raf[0].length() - Wav.HEADER) / 4
            val (_, out) = create(ctx, "$title - mix")
            FlacEncoder(java.io.BufferedOutputStream(out, 1 shl 16), SAMPLE_RATE, total).use { enc ->
                val block = 4096
                val bytes = ByteArray(block * 4)
                val mix = FloatArray(block * 2)
                val pl = ShortArray(block)
                val pr = ShortArray(block)
                val anySolo = solo.any { it }
                var frame = 0L
                while (frame < total) {
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
                    enc.write(pl, pr, frames)
                    frame += frames
                }
            }
        } finally {
            raf.forEach { it.close() }
        }
    }
}
