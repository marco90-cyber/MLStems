package com.mlapps.mlstems.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.mlapps.mlstems.engine.SAMPLE_RATE
import com.mlapps.mlstems.engine.Wav
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/**
 * Player semplice a più tracce: legge gli stem WAV a blocchi dal disco (RAM bassa),
 * li somma con volume / mute / solo e li manda a un AudioTrack.
 */
class MultiStemPlayer(files: List<File>) {

    private val n = files.size
    private val raf = files.map { RandomAccessFile(it, "r") }
    private val totalFrames = (raf[0].length() - Wav.HEADER) / 4

    val gains = FloatArray(n) { 1f }
    val muted = BooleanArray(n)
    val solo = BooleanArray(n)

    @Volatile private var frame = 0L
    @Volatile var isPlaying = false
        private set
    private var thread: Thread? = null

    val positionMs get() = frame * 1000 / SAMPLE_RATE
    val durationMs get() = totalFrames * 1000 / SAMPLE_RATE

    fun play() {
        if (isPlaying) return
        if (frame >= totalFrames) frame = 0
        isPlaying = true
        thread = Thread { loop() }.also { it.start() }
    }

    fun pause() {
        isPlaying = false
        thread?.join()
        thread = null
    }

    fun seekTo(ms: Long) {
        frame = (ms * SAMPLE_RATE / 1000).coerceIn(0, totalFrames)
    }

    fun release() {
        pause()
        raf.forEach { it.close() }
    }

    private fun loop() {
        val block = 4096
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(max(minBuf, block * 16))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val bytes = ByteArray(block * 4)
        val mix = FloatArray(block * 2)
        val out = ShortArray(block * 2)
        while (isPlaying && frame < totalFrames) {
            val frames = min(block.toLong(), totalFrames - frame).toInt()
            mix.fill(0f)
            val anySolo = solo.any { it }
            for (i in 0 until n) {
                val audible = if (anySolo) solo[i] else !muted[i]
                if (!audible) continue
                raf[i].seek(Wav.HEADER + frame * 4)
                raf[i].readFully(bytes, 0, frames * 4)
                val g = gains[i]
                for (k in 0 until frames * 2) {
                    val s = (bytes[2 * k + 1].toInt() shl 8) or (bytes[2 * k].toInt() and 0xFF)
                    mix[k] += s * g
                }
            }
            for (k in 0 until frames * 2) out[k] = mix[k].coerceIn(-32768f, 32767f).toInt().toShort()
            track.write(out, 0, frames * 2)
            frame += frames
        }
        track.stop()
        track.release()
        if (frame >= totalFrames) isPlaying = false
    }
}
