package com.mlapps.mlstems.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * Gli stem sono salvati in FLAC (poco spazio). Per ascoltarli il player ha bisogno di WAV
 * ad accesso diretto: al primo ascolto si decodificano nella cache (che Android può svuotare:
 * in quel caso si rifà da solo).
 */
object StemCache {

    fun wavFor(ctx: Context, projectId: String, src: android.net.Uri, name: String, onProgress: (Float) -> Unit = {}): File {
        val dir = File(ctx.cacheDir, "stems/$projectId").apply { mkdirs() }
        val wav = File(dir, File(name).nameWithoutExtension + ".wav")
        if (wav.exists() && wav.length() > Wav.HEADER) return wav
        val tmp = File(dir, wav.name + ".tmp")
        decodeToWav(ctx, src, tmp, onProgress)
        tmp.renameTo(wav)
        return wav
    }

    fun clear(ctx: Context, projectId: String) {
        File(ctx.cacheDir, "stems/$projectId").deleteRecursively()
    }

    private fun decodeToWav(ctx: Context, src: android.net.Uri, dst: File, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, src, null)
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var bytes = 0L
        val info = MediaCodec.BufferInfo()
        BufferedOutputStream(dst.outputStream(), 1 shl 16).use { out ->
            out.write(Wav.header(0))
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset); buf.get(chunk)
                        if (channels == 2) { out.write(chunk); bytes += chunk.size }
                        else {                       // mono: duplica sui due canali
                            var k = 0
                            while (k + 1 < chunk.size) { out.write(chunk, k, 2); out.write(chunk, k, 2); k += 2; bytes += 4 }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        }
        codec.stop(); codec.release(); extractor.release()
        RandomAccessFile(dst, "rw").use { it.seek(0); it.write(Wav.header(bytes)) }
    }
}
