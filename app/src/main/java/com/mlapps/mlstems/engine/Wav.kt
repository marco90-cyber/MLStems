package com.mlapps.mlstems.engine

import com.mlapps.mlstems.core.FlacEncoder
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/** WAV PCM 16 bit stereo 44,1 kHz con header fisso da 44 byte (il player conta su questo). */
object Wav {
    const val HEADER = 44

    fun header(dataBytes: Long): ByteArray {
        val byteRate = SAMPLE_RATE * 4
        fun i32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return "RIFF".toByteArray() + i32(36 + dataBytes) + "WAVEfmt ".toByteArray() +
            i32(16) + i16(1) + i16(2) + i32(SAMPLE_RATE.toLong()) + i32(byteRate.toLong()) +
            i16(4) + i16(16) + "data".toByteArray() + i32(dataBytes)
    }

    fun writeStem(file: File, left: FloatArray, right: FloatArray) {
        BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
            out.write(header(left.size.toLong() * 4))
            val buf = ByteArray(4096 * 4)
            var i = 0
            while (i < left.size) {
                val m = minOf(4096, left.size - i)
                for (k in 0 until m) {
                    put(buf, k * 4, left[i + k])
                    put(buf, k * 4 + 2, right[i + k])
                }
                out.write(buf, 0, m * 4)
                i += m
            }
        }
    }

    private fun put(b: ByteArray, o: Int, f: Float) {
        val s = (f * 32767f).toInt().coerceIn(-32768, 32767)
        b[o] = s.toByte()
        b[o + 1] = (s shr 8).toByte()
    }

    fun writeShortsLE(out: OutputStream, data: ShortArray, count: Int) {
        val b = ByteArray(count * 2)
        for (i in 0 until count) {
            b[2 * i] = data[i].toByte()
            b[2 * i + 1] = (data[i].toInt() shr 8).toByte()
        }
        out.write(b)
    }
}

/** Scrive uno stem su disco man mano che arriva (FLAC 16 bit stereo, lunghezza nota in anticipo). */
class StemWriter(file: File, totalFrames: Int) : AutoCloseable {
    private val enc = FlacEncoder(BufferedOutputStream(file.outputStream(), 1 shl 16), SAMPLE_RATE, totalFrames.toLong())

    fun write(left: FloatArray, right: FloatArray, count: Int) = enc.write(left, right, count)

    override fun close() = enc.close()
}

/** Nome italiano di uno stem a partire da quello dei modelli. */
fun stemLabel(source: String) = when (source) {
    "drums" -> "Batteria"
    "bass" -> "Basso"
    "other" -> "Altro"
    "vocals" -> "Voce"
    "guitar" -> "Chitarra"
    "piano" -> "Pianoforte"
    else -> source.replaceFirstChar { it.uppercase() }
}
