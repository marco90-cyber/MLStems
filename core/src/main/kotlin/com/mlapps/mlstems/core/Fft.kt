package com.mlapps.mlstems.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT complessa radice-2 (lunghezza potenza di 2), in doppia precisione. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "la lunghezza deve essere una potenza di 2" }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Trasformata sul posto. inverse=true: segno opposto, senza dividere per n. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
