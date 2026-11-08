package com.mlapps.mlstems.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

const val SAMPLE_RATE = 44_100

class DecodedAudio(val left: FloatArray, val right: FloatArray) {
    val frames get() = left.size
}

/** Decodifica qualsiasi audio supportato da Android in stereo float a 44,1 kHz. */
object AudioDecoder {

    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var l = FloatArray(maxOf(1, (durationUs / 1_000_000.0 * rate).toInt() + rate))
        var r = FloatArray(l.size)
        var n = 0

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val size = extractor.readSampleData(buf, 0)
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
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = buf.remaining() / channels
                    if (n + frames > l.size) {
                        l = l.copyOf(maxOf(l.size * 3 / 2, n + frames))
                        r = r.copyOf(l.size)
                    }
                    for (k in 0 until frames) {
                        val a = buf.get() / 32768f
                        var b = a
                        for (c in 1 until channels) { val x = buf.get() / 32768f; if (c == 1) b = x }
                        l[n + k] = a
                        r[n + k] = b
                    }
                    n += frames
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val left = l.copyOf(n)
        val right = r.copyOf(n)
        return if (rate == SAMPLE_RATE) DecodedAudio(left, right)
        else DecodedAudio(resample(left, rate), resample(right, rate))
    }

    /** Ricampionamento lineare semplice (bozza: si può sostituire con uno migliore). */
    private fun resample(x: FloatArray, from: Int): FloatArray {
        val outLen = (x.size.toLong() * SAMPLE_RATE / from).toInt()
        val out = FloatArray(outLen)
        val step = from.toDouble() / SAMPLE_RATE
        for (i in 0 until outLen) {
            val p = i * step
            val i0 = p.toInt().coerceAtMost(x.size - 1)
            val i1 = (i0 + 1).coerceAtMost(x.size - 1)
            val f = (p - i0).toFloat()
            out[i] = x[i0] * (1 - f) + x[i1] * f
        }
        return out
    }
}
