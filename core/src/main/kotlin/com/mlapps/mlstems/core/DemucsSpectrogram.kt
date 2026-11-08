package com.mlapps.mlstems.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Parte "complessa" di HT-Demucs, che ONNX non sa esprimere. Riproduce HTDemucs._spec / _ispec
 * (stesso riempimento, finestra di Hann periodica, normalizzazione, bin di Nyquist e 2 frame per lato scartati).
 * Layout "channels as complex": piano 2c = parte reale del canale c, piano 2c+1 = parte immaginaria.
 * Spettrogramma appiattito come [piani][freq][tempo].
 */
class DemucsSpectrogram(val nfft: Int, val hop: Int, val length: Int) {

    val freqBins = nfft / 2
    val frames = (length + hop - 1) / hop
    private val pad = hop / 2 * 3
    private val rightPad = pad + frames * hop - length
    private val totalFrames = 1 + (length + pad + rightPad) / hop
    private val outSamples = nfft + hop * (totalFrames - 1)
    private val offset = nfft / 2 + pad
    private val fft = Fft(nfft)
    private val window = DoubleArray(nfft) { 0.5 * (1.0 - cos(2.0 * PI * it / nfft)) }
    private val scale = 1.0 / sqrt(nfft.toDouble())
    private val iscale = sqrt(nfft.toDouble())
    private val env = DoubleArray(outSamples).also { e ->
        for (t in 0 until totalFrames) for (i in 0 until nfft) e[t * hop + i] += window[i] * window[i]
    }
    private val re = DoubleArray(nfft)
    private val im = DoubleArray(nfft)

    private fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        val period = 2 * (n - 1)
        var j = ((i % period) + period) % period
        if (j >= n) j = period - j
        return j
    }

    private fun reflectPad(x: FloatArray, left: Int, right: Int): FloatArray =
        FloatArray(x.size + left + right) { x[reflect(it - left, x.size)] }

    /** mix: [canali][length] -> spettrogramma [2*canali][freqBins][frames]. */
    fun forward(mix: Array<FloatArray>): FloatArray {
        val c = mix.size
        val out = FloatArray(2 * c * freqBins * frames)
        for (ch in 0 until c) {
            require(mix[ch].size == length) { "attesi $length campioni" }
            val p = reflectPad(reflectPad(mix[ch], pad, rightPad), nfft / 2, nfft / 2)
            for (t in 0 until frames) {
                val start = 2 * hop + t * hop
                for (i in 0 until nfft) { re[i] = p[start + i] * window[i]; im[i] = 0.0 }
                fft.transform(re, im, false)
                for (f in 0 until freqBins) {
                    out[((2 * ch) * freqBins + f) * frames + t] = (re[f] * scale).toFloat()
                    out[((2 * ch + 1) * freqBins + f) * frames + t] = (im[f] * scale).toFloat()
                }
            }
        }
        return out
    }

    /** spec[specOffset...] = [2*canali][freqBins][frames] -> forma d'onda [canali][length]. */
    fun inverse(spec: FloatArray, specOffset: Int, channels: Int): Array<FloatArray> {
        val result = Array(channels) { FloatArray(length) }
        val buf = DoubleArray(outSamples)
        for (ch in 0 until channels) {
            buf.fill(0.0)
            for (t in 0 until frames) {
                re.fill(0.0); im.fill(0.0)
                for (k in 0 until freqBins) {
                    val zr = spec[specOffset + ((2 * ch) * freqBins + k) * frames + t].toDouble()
                    val zi = spec[specOffset + ((2 * ch + 1) * freqBins + k) * frames + t].toDouble()
                    re[k] = zr
                    im[k] = if (k == 0) 0.0 else zi          // irfft ignora l'immaginario del DC
                    if (k > 0) { re[nfft - k] = zr; im[nfft - k] = -zi }
                }
                // il bin di Nyquist (k = nfft/2) resta a zero, come in demucs
                fft.transform(re, im, true)
                val base = (t + 2) * hop
                for (i in 0 until nfft) buf[base + i] += re[i] / nfft * iscale * window[i]
            }
            for (j in 0 until length) result[ch][j] = (buf[offset + j] / env[offset + j]).toFloat()
        }
        return result
    }
}
