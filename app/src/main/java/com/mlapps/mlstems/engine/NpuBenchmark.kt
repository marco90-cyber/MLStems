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
    fun probe(ctx: Context, model: File): String? = try {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { o ->
            Accel.configure(ctx, o)
            env.createSession(model.absolutePath, o).close()
        }
        null
    } catch (e: Throwable) {
        e.message ?: e.javaClass.simpleName
    }

    private class Out(val loadMs: Long, val avgMs: Long, val y: FloatArray)

    private fun time(ctx: Context, model: File, npu: Boolean, runs: Int, strict: Boolean = false): Out {
        val env = OrtEnvironment.getEnvironment()
        val t0 = System.currentTimeMillis()
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(4)
            if (npu) Accel.configure(ctx, o)
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

    fun run(ctx: Context, model: File, onLine: (String) -> Unit) {
        if (!model.exists()) { onLine("Scarica prima il modello Spleeter 4 stem."); return }
        val runs = 5
        val cpu = try { time(ctx, model, false, runs).also { onLine("CPU: caricamento ${it.loadMs} ms · ${it.avgMs} ms per blocco") } }
        catch (e: Throwable) { onLine("CPU: errore ${e.message}"); return }
        if (!Accel.available) { onLine("Questa build non include l'NPU."); return }
        try {
            val npu = time(ctx, model, true, runs)
            val diff = cpu.y.indices.maxOf { abs(cpu.y[it] - npu.y[it]) }
            onLine("NPU: caricamento ${npu.loadMs} ms · ${npu.avgMs} ms per blocco")
            onLine("Velocità NPU rispetto alla CPU: ×${"%.2f".format(cpu.avgMs.toFloat() / npu.avgMs.coerceAtLeast(1))}")
            onLine("Differenza massima sul risultato: ${"%.4f".format(diff)}")
        } catch (e: Throwable) {
            onLine("NPU: errore ${e.message}")
            return
        }
        onLine(try { time(ctx, model, true, 1, strict = true); "NPU senza ripiego sulla CPU: riuscita (rete intera sull'NPU)" }
        catch (e: Throwable) { "NPU senza ripiego sulla CPU: non riuscita (parte della rete resta sulla CPU)" })
    }
}
