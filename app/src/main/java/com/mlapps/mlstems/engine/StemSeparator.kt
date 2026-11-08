package com.mlapps.mlstems.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mlapps.mlstems.data.StemModel
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.sqrt

class StemAudio(val name: String, val left: FloatArray, val right: FloatArray)

interface StemSeparator : AutoCloseable {
    fun separate(audio: DecodedAudio, onProgress: (Float) -> Unit): List<StemAudio>
}

/**
 * Separatore generico basato su ONNX Runtime.
 *
 * Assume un modello esportato con la STFT dentro il grafo:
 *   input  [1, 2, segmentSamples]            (forma d'onda stereo)
 *   output [1, nStems, 2, segmentSamples]    (una forma d'onda per stem)
 *
 * Se un modello ha un formato diverso (es. Spleeter con spettrogramma esterno) serve un
 * adattatore dedicato che implementi StemSeparator: il resto dell'app non cambia.
 *
 * Il brano viene diviso in blocchi con sovrapposizione e ricomposto con finestra triangolare,
 * così la RAM resta bassa e non si sentono salti tra un blocco e l'altro.
 */
class OnnxSeparator(private val model: StemModel, modelFile: File) : StemSeparator {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6)) }
    )
    private val inputName = session.inputNames.first()

    override fun separate(audio: DecodedAudio, onProgress: (Float) -> Unit): List<StemAudio> {
        val seg = model.segmentSamples
        val hop = (seg * (1f - model.overlap)).toInt().coerceAtLeast(1)
        val n = audio.frames
        val nStems = model.stems.size

        val out = Array(nStems) { Array(2) { FloatArray(n) } }
        val weight = FloatArray(n)
        val window = FloatArray(seg) { min(it + 1, seg - it).toFloat() }

        var start = 0
        while (true) {
            val len = min(seg, n - start)
            val chunk = FloatArray(2 * seg)
            System.arraycopy(audio.left, start, chunk, 0, len)
            System.arraycopy(audio.right, start, chunk, seg, len)

            // normalizzazione come in Demucs
            var mean = 0f; var std = 1f
            if (model.normalize) {
                var s = 0.0
                for (i in 0 until len) s += (chunk[i] + chunk[seg + i]) * 0.5
                mean = (s / len).toFloat()
                var v = 0.0
                for (i in 0 until len) { val d = (chunk[i] + chunk[seg + i]) * 0.5 - mean; v += d * d }
                std = sqrt(v / len).toFloat() + 1e-8f
                for (i in chunk.indices) chunk[i] = (chunk[i] - mean) / std
            }

            OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, 2, seg.toLong())).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val y = result[0].value as Array<Array<Array<FloatArray>>>   // [1][stems][2][seg]
                    for (s in 0 until nStems) for (c in 0 until 2) {
                        val src = y[0][s][c]
                        val dst = out[s][c]
                        for (i in 0 until len) dst[start + i] += (src[i] * std + mean) * window[i]
                    }
                    for (i in 0 until len) weight[start + i] += window[i]
                }
            }

            onProgress(((start + len).toFloat() / n).coerceIn(0f, 1f))
            if (start + seg >= n) break
            start += hop
        }

        for (s in 0 until nStems) for (c in 0 until 2) {
            val d = out[s][c]
            for (i in 0 until n) if (weight[i] > 0f) d[i] /= weight[i]
        }
        return model.stems.mapIndexed { i, name -> StemAudio(name, out[i][0], out[i][1]) }
    }

    override fun close() {
        session.close()
    }
}
