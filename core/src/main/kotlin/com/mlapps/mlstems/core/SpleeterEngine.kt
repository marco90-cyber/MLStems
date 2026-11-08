package com.mlapps.mlstems.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Spleeter 4 stem (voce, batteria, basso, altro) su ONNX Runtime: quattro U-Net, una per stem.
 * STFT con finestra di Hann periodica (4096 / hop 1024), maschera "soft ratio" (esponente 2) e
 * maschera estesa alle frequenze alte con la media per frame ("average").
 * Il brano è elaborato a blocchi di 512 frame con somma e sovrapposizione in streaming.
 * Le quattro maschere sommano a 1: la somma degli stem ricostruisce il brano.
 */
class SpleeterEngine(
    private val dir: File,
    private val threads: Int = 4,
    private val prefix: String = "spleeter4_",
    private val configure: (OrtSession.SessionOptions) -> Unit = {},
) : StemEngine {

    override val sources = listOf("vocals", "drums", "bass", "other")

    private val fft = Fft(NFFT)
    private val window = DoubleArray(NFFT) { 0.5 * (1.0 - cos(2.0 * PI * it / NFFT)) }

    override fun separate(left: FloatArray, right: FloatArray, sink: StemSink, progress: (Float) -> Unit) {
        val n = left.size
        require(right.size == n) { "canali di lunghezza diversa" }
        if (n == 0) return
        val xs = arrayOf(left, right)
        val frames = (PAD + n + HOP - 1) / HOP
        val splits = (frames + T - 1) / T
        val nStems = sources.size
        val env = OrtEnvironment.getEnvironment()

        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads); configure(this) }
        val sessions = sources.map { env.createSession(File(dir, "$prefix$it.onnx").absolutePath, options) }
        try {
            val cap = T * HOP + NFFT
            val acc = Array(nStems) { Array(2) { FloatArray(cap) } }
            val wsum = FloatArray(cap)
            var base = 0
            var emitted = 0

            val re = DoubleArray(NFFT)
            val im = DoubleArray(NFFT)
            val specRe = Array(2) { FloatArray(T * BINS) }
            val specIm = Array(2) { FloatArray(T * BINS) }
            val mag = FloatArray(2 * T * F)
            val est = Array(nStems) { FloatArray(2 * T * F) }
            val mask = Array(nStems) { DoubleArray(BINS) }
            val sumsq = DoubleArray(F)

            for (k in 0 until splits) {
                val a = k * T
                val b = min(frames, a + T)
                val nf = b - a
                mag.fill(0f)

                // STFT del blocco
                for (c in 0 until 2) for (t in 0 until nf) {
                    val start = (a + t) * HOP - PAD
                    for (i in 0 until NFFT) {
                        val j = start + i
                        re[i] = if (j in 0 until n) xs[c][j] * window[i] else 0.0
                        im[i] = 0.0
                    }
                    fft.transform(re, im, false)
                    for (f in 0 until BINS) {
                        specRe[c][t * BINS + f] = re[f].toFloat()
                        specIm[c][t * BINS + f] = im[f].toFloat()
                    }
                    for (f in 0 until F) mag[(c * T + t) * F + f] = hypot(re[f], im[f]).toFloat()
                }

                // stime delle quattro reti
                OnnxTensor.createTensor(env, FloatBuffer.wrap(mag), longArrayOf(2, 1, T.toLong(), F.toLong())).use { input ->
                    for (s in 0 until nStems) {
                        sessions[s].run(mapOf("x" to input)).use { res ->
                            val buf = (res.get("y").get() as OnnxTensor).floatBuffer.duplicate()
                            buf.position(0)
                            buf.get(est[s])
                        }
                    }
                }

                // maschere, iSTFT e somma con sovrapposizione
                for (c in 0 until 2) for (t in 0 until nf) {
                    val eo = (c * T + t) * F
                    for (f in 0 until F) {
                        var sum = EPS
                        for (s in 0 until nStems) { val e = est[s][eo + f].toDouble(); sum += e * e }
                        sumsq[f] = sum
                    }
                    val frameStart = (a + t) * HOP - base
                    for (s in 0 until nStems) {
                        var mean = 0.0
                        for (f in 0 until F) {
                            val e = est[s][eo + f].toDouble()
                            val m = (e * e + EPS / nStems) / sumsq[f]
                            mask[s][f] = m
                            mean += m
                        }
                        mean /= F
                        for (f in F until BINS) mask[s][f] = mean

                        for (f in 0 until BINS) {
                            val m = mask[s][f]
                            re[f] = specRe[c][t * BINS + f] * m
                            im[f] = specIm[c][t * BINS + f] * m
                        }
                        im[0] = 0.0
                        im[NFFT / 2] = 0.0
                        for (f in 1 until NFFT / 2) { re[NFFT - f] = re[f]; im[NFFT - f] = -im[f] }
                        fft.transform(re, im, true)
                        val out = acc[s][c]
                        for (i in 0 until NFFT) out[frameStart + i] += (re[i] / NFFT * window[i]).toFloat()
                    }
                    if (c == 0) for (i in 0 until NFFT) wsum[frameStart + i] += (window[i] * window[i]).toFloat()
                }

                // campioni ormai definitivi
                val finalPadded = if (b < frames) b * HOP else Int.MAX_VALUE
                val end = if (b < frames) min(n, finalPadded - PAD) else n
                val count = end - emitted
                if (count > 0) {
                    val l = FloatArray(count)
                    val r = FloatArray(count)
                    for (s in 0 until nStems) {
                        for (i in 0 until count) {
                            val idx = emitted + PAD - base + i
                            val w = wsum[idx]
                            l[i] = if (w > 1e-8f) acc[s][0][idx] / w else 0f
                            r[i] = if (w > 1e-8f) acc[s][1][idx] / w else 0f
                        }
                        sink.write(s, l, r, count)
                    }
                    emitted = end
                }
                if (b < frames) {
                    val shift = b * HOP - base
                    for (s in 0 until nStems) for (c in 0 until 2) {
                        System.arraycopy(acc[s][c], shift, acc[s][c], 0, cap - shift)
                        acc[s][c].fill(0f, cap - shift, cap)
                    }
                    System.arraycopy(wsum, shift, wsum, 0, cap - shift)
                    wsum.fill(0f, cap - shift, cap)
                    base += shift
                }
                progress((k + 1f) / splits)
            }
        } finally {
            sessions.forEach { it.close() }
            options.close()
        }
    }

    companion object {
        const val NFFT = 4096
        const val HOP = 1024
        const val PAD = NFFT - HOP
        const val BINS = NFFT / 2 + 1
        const val T = 512        // frame per blocco, fissato dall'esportazione
        const val F = 1024       // bin modellati dalla rete (fino a circa 11 kHz)
        const val EPS = 1e-10
    }
}
