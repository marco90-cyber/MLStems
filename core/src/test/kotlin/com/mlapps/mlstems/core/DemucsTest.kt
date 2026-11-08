package com.mlapps.mlstems.core

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class DemucsTest {

    @Test
    fun spectrogramRoundTrip() {
        val n = 343_980
        val rnd = Random(1)
        val x = Array(2) { FloatArray(n) { rnd.nextFloat() * 2f - 1f } }
        val sp = DemucsSpectrogram(4096, 1024, n)
        val spec = sp.forward(x)
        val y = sp.inverse(spec, 0, 2)
        var all = 0f; var interior = 0f
        for (c in 0 until 2) for (i in 0 until n) {
            val e = abs(x[c][i] - y[c][i])
            all = maxOf(all, e)
            if (i in 4096 until n - 4096) interior = maxOf(interior, e)
        }
        println("ROUNDTRIP max errore totale=$all  interno=$interior  frame=${sp.frames}")
        assertTrue(interior < 1e-3f, "ricostruzione STFT errata: $interior")
    }

    private fun synth(seconds: Int): Array<FloatArray> {
        val sr = 44100
        val n = seconds * sr
        val rnd = Random(7)
        val l = FloatArray(n); val r = FloatArray(n)
        for (i in 0 until n) {
            val t = i / sr.toDouble()
            val bass = 0.25 * sin(2 * PI * 55 * t) * (0.6 + 0.4 * sin(2 * PI * 0.5 * t))
            val kt = t % 0.5
            val kick = 0.5 * sin(2 * PI * (60 + 80 * kotlin.math.exp(-kt * 30)) * kt) * kotlin.math.exp(-kt * 12)
            val ht = t % 0.25
            val hat = if (ht < 0.03) (rnd.nextDouble() * 2 - 1) * 0.15 * (1 - ht / 0.03) else 0.0
            val chord = 0.08 * (sin(2 * PI * 261.6 * t) + sin(2 * PI * 329.6 * t) + sin(2 * PI * 392.0 * t))
            val lead = 0.1 * sin(2 * PI * (440 + 40 * sin(2 * PI * 5 * t)) * t) * (if ((t % 2) < 1) 1.0 else 0.0)
            l[i] = (bass + kick + hat + chord + lead).toFloat()
            r[i] = (bass + kick + hat * 0.6 + chord * 0.8 + lead * 1.1).toFloat()
        }
        return arrayOf(l, r)
    }

    @Test
    fun separateSyntheticWithRealModel() {
        val path = System.getenv("MLSTEMS_MODEL_JSON")
        if (path == null) { println("SKIP: MLSTEMS_MODEL_JSON non impostata"); return }
        val cfg = DemucsConfig.load(File(path))
        val engine = DemucsEngine(cfg, threads = 4)
        val mix = synth(15)
        val n = mix[0].size
        val stems = Array(cfg.sources.size) { Array(2) { FloatArray(n) } }
        val pos = IntArray(cfg.sources.size)
        val sink = object : StemSink {
            override fun write(stem: Int, left: FloatArray, right: FloatArray, count: Int) {
                System.arraycopy(left, 0, stems[stem][0], pos[stem], count)
                System.arraycopy(right, 0, stems[stem][1], pos[stem], count)
                pos[stem] += count
            }
        }
        val t0 = System.nanoTime()
        engine.separate(mix[0], mix[1], sink) { }
        val secs = (System.nanoTime() - t0) / 1e9
        println("SEPARAZIONE ${cfg.name}: ${n / 44100.0}s di audio in ${"%.1f".format(secs)}s")

        for (s in stems.indices) assertTrue(pos[s] == n, "stem ${cfg.sources[s]}: scritti ${pos[s]} di $n campioni")
        var sig = 0.0; var err = 0.0
        val rms = DoubleArray(stems.size)
        for (c in 0 until 2) for (i in 0 until n) {
            var sum = 0.0
            for (s in stems.indices) { val v = stems[s][c][i].toDouble(); assertTrue(v.isFinite(), "valore non finito"); sum += v; rms[s] += v * v }
            sig += mix[c][i].toDouble().let { it * it }
            err += (mix[c][i] - sum).let { it * it }
        }
        val snr = 10 * log10(sig / err)
        println("SOMMA STEM vs MIX: SNR = ${"%.1f".format(snr)} dB")
        for (s in stems.indices) println("  stem ${cfg.sources[s]}: rms=${"%.4f".format(kotlin.math.sqrt(rms[s] / (2.0 * n)))}")
        // continuità ai confini dei blocchi (i blocchi iniziano a 257985 e 515970)
        for (b in intArrayOf(257_985, 515_970)) {
            var worst = 0f; var typical = 0f
            for (s in stems.indices) {
                worst = maxOf(worst, abs(stems[s][0][b] - stems[s][0][b - 1]))
                for (k in b - 2000 until b - 1000) typical = maxOf(typical, abs(stems[s][0][k] - stems[s][0][k - 1]))
            }
            println("  confine $b: salto=${"%.4f".format(worst)} tipico=${"%.4f".format(typical)}")
        }
        assertTrue(snr > 10.0, "la somma degli stem non ricostruisce il mix (SNR $snr dB)")
    }
}
