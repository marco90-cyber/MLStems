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
                // segnale a banda limitata: il bin di Nyquist scartato da demucs non ha energia
        val x = Array(2) { c -> FloatArray(n) { i -> (0.4 * sin(2 * PI * 440 * i / 44100.0 + c) + 0.3 * sin(2 * PI * 3300 * i / 44100.0) + 0.2 * sin(2 * PI * 9000 * i / 44100.0)).toFloat() } }
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
        assertTrue(interior < 1e-4f, "ricostruzione STFT errata: $interior")
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

    private fun check(engine: StemEngine, name: String, minSnr: Double) {
        val mix = synth(15)
        val n = mix[0].size
        val ns = engine.sources.size
        val stems = Array(ns) { Array(2) { FloatArray(n) } }
        val pos = IntArray(ns)
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
        println("SEPARAZIONE $name: ${n / 44100.0}s di audio in ${"%.1f".format(secs)}s")

        for (s in 0 until ns) assertTrue(pos[s] == n, "stem ${engine.sources[s]}: scritti ${pos[s]} di $n campioni")
        var sig = 0.0; var err = 0.0
        val rms = DoubleArray(ns)
        for (c in 0 until 2) for (i in 0 until n) {
            var sum = 0.0
            for (s in 0 until ns) { val v = stems[s][c][i].toDouble(); assertTrue(v.isFinite(), "valore non finito"); sum += v; rms[s] += v * v }
            sig += mix[c][i].toDouble().let { it * it }
            err += (mix[c][i] - sum).let { it * it }
        }
        val snr = 10 * log10(sig / err)
        println("SOMMA STEM vs MIX: SNR = ${"%.1f".format(snr)} dB")
        for (s in 0 until ns) println("  stem ${engine.sources[s]}: rms=${"%.4f".format(kotlin.math.sqrt(rms[s] / (2.0 * n)))}")
        assertTrue(snr > minSnr, "la somma degli stem non ricostruisce il mix (SNR $snr dB)")
    }

    @Test
    fun demucsRealModel() {
        val path = System.getenv("MLSTEMS_MODEL_JSON")
        if (path == null) { println("SKIP demucs: MLSTEMS_MODEL_JSON non impostata"); return }
        val cfg = DemucsConfig.load(File(path))
        check(DemucsEngine(cfg, threads = 4), cfg.name, 10.0)
    }

    @Test
    fun spleeterRealModel() {
        val dir = System.getenv("MLSTEMS_SPLEETER_DIR")
        if (dir == null) { println("SKIP spleeter: MLSTEMS_SPLEETER_DIR non impostata"); return }
        check(SpleeterEngine(File(dir), threads = 4), "spleeter4", 60.0)
    }

    @Test
    fun flacRoundTrip() {
        val ffmpeg = listOf("/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg").firstOrNull { File(it).exists() }
        if (ffmpeg == null) { println("SKIP flac: ffmpeg assente"); return }
        val n = 44_100 * 3 + 1234                      // coda più corta di un blocco
        val rnd = Random(7)
        val l = ShortArray(n) { i ->
            if (i in 50_000..60_000) 0
            else (9000 * sin(2 * PI * 330 * i / 44100.0) + rnd.nextInt(-300, 300)).toInt().toShort()
        }
        val r = ShortArray(n) { i -> if (i in 100_000..101_000) Short.MAX_VALUE else (l[i] * 0.6 + rnd.nextInt(-50, 50)).toInt().toShort() }
        val f = File.createTempFile("flt", ".flac")
        FlacEncoder(f.outputStream().buffered(), 44100, n.toLong()).use { e ->
            e.write(l, r, 70_000); e.write(l.copyOfRange(70_000, n), r.copyOfRange(70_000, n), n - 70_000)
        }
        val raw = File.createTempFile("flt", ".raw")
        val pr = ProcessBuilder(ffmpeg, "-y", "-v", "error", "-i", f.path, "-f", "s16le", "-acodec", "pcm_s16le", raw.path).redirectErrorStream(true).start()
        val log = pr.inputStream.bufferedReader().readText()
        check(pr.waitFor() == 0 && log.isBlank()) { "ffmpeg: $log" }
        val b = raw.readBytes()
        check(b.size == n * 4) { "lunghezza ${b.size} invece di ${n * 4}" }
        for (i in 0 until n) {
            val a = (b[4 * i].toInt() and 0xFF) or (b[4 * i + 1].toInt() shl 8)
            val c = (b[4 * i + 2].toInt() and 0xFF) or (b[4 * i + 3].toInt() shl 8)
            check(a.toShort() == l[i] && c.toShort() == r[i]) { "campione $i diverso" }
        }
        println("FLAC ok: ${f.length()} byte contro ${n * 4} (${100 * f.length() / (n * 4)}%)")
    }
}
