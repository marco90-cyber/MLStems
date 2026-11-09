package com.mlapps.mlstems.engine

import com.mlapps.mlstems.core.FlacEncoder
import java.io.BufferedOutputStream
import java.io.OutputStream

/** Scrive audio 16 bit stereo 44,1 kHz in un formato. */
interface PcmWriter : AutoCloseable {
    fun write(l: ShortArray, r: ShortArray, n: Int)

    /** Campioni float in [-1, 1]. */
    fun writeFloat(l: FloatArray, r: FloatArray, n: Int) {
        val bl = ShortArray(minOf(n, 16384))
        val br = ShortArray(bl.size)
        var i = 0
        while (i < n) {
            val m = minOf(bl.size, n - i)
            for (k in 0 until m) {
                bl[k] = (l[i + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                br[k] = (r[i + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            write(bl, br, m)
            i += m
        }
    }
}

enum class AudioFormat(val key: String, val ext: String, val mime: String, val label: String) {
    MP3("mp3", "mp3", "audio/mpeg", "MP3 320 kbps"),
    FLAC("flac", "flac", "audio/flac", "FLAC (senza perdita)"),
    WAV("wav", "wav", "audio/x-wav", "WAV (senza perdita, file grandi)");

    fun open(out: OutputStream, totalFrames: Long): PcmWriter = when (this) {
        FLAC -> object : PcmWriter {
            private val enc = FlacEncoder(BufferedOutputStream(out, 1 shl 16), SAMPLE_RATE, totalFrames)
            override fun write(l: ShortArray, r: ShortArray, n: Int) = enc.write(l, r, n)
            override fun close() = enc.close()
        }
        WAV -> object : PcmWriter {
            private val o = BufferedOutputStream(out, 1 shl 16).also { it.write(Wav.header(totalFrames * 4)) }
            private val buf = ByteArray(16384 * 4)
            override fun write(l: ShortArray, r: ShortArray, n: Int) {
                var i = 0
                while (i < n) {
                    val m = minOf(16384, n - i)
                    for (k in 0 until m) {
                        val a = l[i + k].toInt(); val b = r[i + k].toInt()
                        buf[4 * k] = a.toByte(); buf[4 * k + 1] = (a shr 8).toByte()
                        buf[4 * k + 2] = b.toByte(); buf[4 * k + 3] = (b shr 8).toByte()
                    }
                    o.write(buf, 0, m * 4)
                    i += m
                }
            }
            override fun close() = o.close()
        }
        MP3 -> object : PcmWriter {
            private val o = BufferedOutputStream(out, 1 shl 16)
            private val lame = com.naman14.androidlame.LameBuilder()
                .setInSampleRate(SAMPLE_RATE).setOutSampleRate(SAMPLE_RATE)
                .setOutChannels(2).setOutBitrate(320).setQuality(2)
                .setMode(com.naman14.androidlame.LameBuilder.Mode.STEREO)
                .build()
            private var buf = ByteArray(1)
            override fun write(l: ShortArray, r: ShortArray, n: Int) {
                val need = (1.25 * n).toInt() + 7200
                if (buf.size < need) buf = ByteArray(need)
                val bytes = lame.encode(l, r, n, buf)
                if (bytes > 0) o.write(buf, 0, bytes)
            }
            override fun close() {
                if (buf.size < 7200) buf = ByteArray(7200)
                val bytes = lame.flush(buf)
                if (bytes > 0) o.write(buf, 0, bytes)
                lame.close()
                o.close()
            }
        }
    }

    companion object {
        fun of(key: String?, default: AudioFormat) = entries.firstOrNull { it.key == key } ?: default
    }
}
