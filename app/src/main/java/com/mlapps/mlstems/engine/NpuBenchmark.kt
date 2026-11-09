package com.mlapps.mlstems.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs

/** Prova CPU contro NPU sulla rete Spleeter (ingresso fisso [2, 1, 512, 1024]) e riporta i tempi. */
object NpuBenchmark {

    private val shape = longArrayOf(2, 1, 512, 1024)
    private fun input() = FloatArray(2 * 512 * 1024) { ((it * 7919L) % 1000).toFloat() / 1000f }

    /** null se la rete parte sull'NPU, altrimenti il messaggio d'errore. */
    fun probe(ctx: Context, model: File, variant: String): String? = try {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { o ->
            Accel.configure(ctx, o, variant)
            env.createSession(model.absolutePath, o).close()
        }
        null
    } catch (e: Throwable) {
        e.message ?: e.javaClass.simpleName
    }

    private class Out(val loadMs: Long, val avgMs: Long, val y: FloatArray)

    private fun time(ctx: Context, model: File, variant: String?, runs: Int, strict: Boolean = false): Out {
        val env = OrtEnvironment.getEnvironment()
        val t0 = System.currentTimeMillis()
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(4)
            if (variant != null) Accel.configure(ctx, o, variant)
            if (strict) o.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            env.createSession(model.absolutePath, o).use { s ->
                val load = System.currentTimeMillis() - t0
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input()), shape).use { x ->
                    var y = FloatArray(0)
                    fun once(): Long {
                        val t = System.currentTimeMillis()
                        s.run(mapOf("x" to x)).use { r ->
                            val buf = (r[0] as OnnxTensor).floatBuffer
                            y = FloatArray(buf.remaining()).also { buf.get(it) }
                        }
                        return System.currentTimeMillis() - t
                    }
                    once()                                        // riscaldamento
                    val total = (1..runs).sumOf { once() }
                    return Out(load, total / runs, y)
                }
            }
        }
    }

    /** Prova CPU e ogni variante QNN; salva nelle impostazioni la più veloce (o nessuna se la CPU vince). */
    fun run(ctx: Context, model: File, onLine: (String) -> Unit) {
        if (!model.exists()) { onLine("Scarica prima il modello Spleeter 4 stem."); return }
        val runs = 5
        val cpu = try { time(ctx, model, null, runs).also { onLine("CPU: ${it.avgMs} ms per blocco (caricamento ${it.loadMs} ms)") } }
        catch (e: Throwable) { onLine("CPU: errore ${e.message}"); return }
        var best: String? = null
        var bestMs = (cpu.avgMs * 0.8).toLong()   // serve almeno ×1,25 per scegliere l'acceleratore
        for (v in Accel.variants) {
            try {
                val r = time(ctx, model, v, runs)
                val diff = cpu.y.indices.maxOf { abs(cpu.y[it] - npu(r)[it]) }
                onLine("$v: ${r.avgMs} ms per blocco (×${"%.2f".format(cpu.avgMs.toFloat() / r.avgMs.coerceAtLeast(1))}), caricamento ${r.loadMs} ms, differenza ${"%.4f".format(diff)}")
                if (r.avgMs < bestMs && diff < 0.05f) { best = v; bestMs = r.avgMs }
            } catch (e: Throwable) {
                onLine("$v: errore ${(e.message ?: "").take(160)}")
            }
        }
        ctx.getSharedPreferences("settings", 0).edit().putString("npu_variant", best ?: "").apply()
        onLine(if (best != null) "Conviene: $best (la scelgo per l'elaborazione)" else "Nessuna variante batte la CPU: resta la CPU.")
    }

    private fun npu(r: Out) = r.y
}
