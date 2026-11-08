package com.mlapps.mlstems.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.mlapps.mlstems.engine.Wav
import java.io.File
import java.io.RandomAccessFile

/** Esporta in Download/MLStems (MediaStore, nessun permesso richiesto da Android 10). */
object Exporter {

    private fun create(ctx: Context, name: String): Pair<Uri, java.io.OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.wav")
            put(MediaStore.Downloads.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MLStems")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        return uri to ctx.contentResolver.openOutputStream(uri)!!
    }

    fun exportStem(ctx: Context, title: String, stemName: String, file: File) {
        val (_, out) = create(ctx, "$title - $stemName")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Mix finale con i volumi / mute / solo correnti. */
    fun exportMix(ctx: Context, title: String, files: List<File>, gains: FloatArray, muted: BooleanArray, solo: BooleanArray) {
        val raf = files.map { RandomAccessFile(it, "r") }
        try {
            val total = (raf[0].length() - Wav.HEADER) / 4
            val (_, out) = create(ctx, "$title - mix")
            out.use { o ->
                o.write(Wav.header(total * 4))
                val block = 4096
                val bytes = ByteArray(block * 4)
                val mix = FloatArray(block * 2)
                val pcm = ShortArray(block * 2)
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
                    for (k in 0 until frames * 2) pcm[k] = mix[k].coerceIn(-32768f, 32767f).toInt().toShort()
                    Wav.writeShortsLE(o, pcm, frames * 2)
                    frame += frames
                }
            }
        } finally {
            raf.forEach { it.close() }
        }
    }
}
