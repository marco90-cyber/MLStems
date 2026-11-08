package com.mlapps.mlstems.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Riceve gli stem man mano che sono pronti. Per ogni stem i blocchi arrivano in ordine, senza buchi. */
interface StemSink {
    fun write(stem: Int, left: FloatArray, right: FloatArray, count: Int)
}

/** Un motore di separazione: modello + pre/post-elaborazione. */
interface StemEngine {
    val sources: List<String>
    fun separate(left: FloatArray, right: FloatArray, sink: StemSink, progress: (Float) -> Unit)
}

class DemucsMember(val file: File, val weights: DoubleArray?)

class DemucsConfig(
    val name: String,
    val sources: List<String>,
    val channels: Int,
    val segment: Int,
    val nfft: Int,
    val hop: Int,
    val frames: Int,
    val members: List<DemucsMember>,
) {
    companion object {
        /** Legge il file .json che accompagna il modello (o l'elenco dei membri di un "bag"). */
        fun load(json: File): DemucsConfig {
            val o = Json.parseToJsonElement(json.readText()).jsonObject
            val sources = o.getValue("sources").jsonArray.map { it.jsonPrimitive.content }
            val bag = o["bag"]?.jsonPrimitive?.boolean == true
            val members = if (bag) {
                o.getValue("models").jsonArray.map {
                    val m = it.jsonObject
                    DemucsMember(
                        File(json.parentFile, m.getValue("model").jsonPrimitive.content),
                        m.getValue("weights").jsonArray.map { w -> w.jsonPrimitive.double }.toDoubleArray(),
                    )
                }
            } else {
                listOf(DemucsMember(File(json.parentFile, json.nameWithoutExtension + ".onnx"), null))
            }
            return DemucsConfig(
                name = o.getValue("name").jsonPrimitive.content,
                sources = sources,
                channels = o.getValue("audio_channels").jsonPrimitive.int,
                segment = o.getValue("segment_samples").jsonPrimitive.int,
                nfft = o.getValue("nfft").jsonPrimitive.int,
                hop = o.getValue("hop").jsonPrimitive.int,
                frames = o.getValue("frames").jsonPrimitive.int,
                members = members,
            )
        }
    }
}

/**
 * HT-Demucs (anche 6 stem e "bag" Fine-Tuned) su ONNX Runtime.
 * Stessa procedura di demucs.apply_model: normalizzazione globale, blocchi di 7,8 s con 25% di
 * sovrapposizione, dissolvenza triangolare. L'output viene finalizzato da sinistra a destra, quindi
 * la memoria non cresce con la durata del brano.
 */
class DemucsEngine(
    private val config: DemucsConfig,
    private val threads: Int = 4,
    private val overlap: Float = 0.25f,
) : StemEngine {

    override val sources get() = config.sources

    private val spectro = DemucsSpectrogram(config.nfft, config.hop, config.segment)
    private val weight = transitionWeight(config.segment)

    init {
        require(spectro.frames == config.frames) { "STFT: ${spectro.frames} frame invece di ${config.frames}" }
    }

    override fun separate(left: FloatArray, right: FloatArray, sink: StemSink, progress: (Float) -> Unit) {
        val total = left.size
        require(right.size == total) { "canali di lunghezza diversa" }
        if (total == 0) return
        val stats = referenceStats(left, right)
        val members = config.members
        members.forEachIndexed { index, member ->
            val owned = ownedStems(member)
            runMember(member, owned, left, right, total, stats, sink) { p ->
                progress((index + p) / members.size)
            }
        }
        progress(1f)
    }

    /** Stem che questo membro produce: tutti, oppure quelli con peso 1 (miscele frazionarie non supportate). */
    private fun ownedStems(member: DemucsMember): List<Int> {
        val w = member.weights ?: return config.sources.indices.toList()
        val owned = w.indices.filter { w[it] != 0.0 }
        require(owned.all { w[it] == 1.0 }) { "pesi frazionari nel bag non supportati" }
        return owned
    }

    private class Stats(val mean: Double, val gain: Double)

    /** demucs normalizza con media e deviazione standard del mono dell'intero brano. */
    private fun referenceStats(l: FloatArray, r: FloatArray): Stats {
        var s = 0.0
        for (i in l.indices) s += (l[i] + r[i]) * 0.5
        val mean = s / l.size
        var v = 0.0
        for (i in l.indices) { val d = (l[i] + r[i]) * 0.5 - mean; v += d * d }
        return Stats(mean, sqrt(v / l.size) + 1e-8)
    }

    private fun runMember(
        member: DemucsMember, owned: List<Int>, left: FloatArray, right: FloatArray, total: Int,
        stats: Stats, sink: StemSink, progress: (Float) -> Unit,
    ) {
        val env = OrtEnvironment.getEnvironment()
        val seg = config.segment
        val stride = max(1, ((1f - overlap) * seg).toInt())
        val ch = config.channels
        val nStems = config.sources.size
        val freq = spectro.freqBins
        val fr = spectro.frames
        val acc = Accumulator(owned.size * ch, seg + stride)
        val offsets = (0 until total step stride).toList()

        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            env.createSession(member.file.absolutePath, options).use { session ->
                val names = session.inputNames.toList()
                val mixName = if ("mix" in names) "mix" else names[0]
                val magName = if ("mag" in names) "mag" else names[1]
                val input = arrayOf(FloatArray(seg), FloatArray(seg))
                val specTmp = FloatArray(2 * ch * freq * fr)

                offsets.forEachIndexed { index, offset ->
                    val length = min(total - offset, seg)
                    val delta = seg - length
                    val start = offset - delta / 2
                    val lo = max(0, start)
                    val hi = min(total, start + seg)
                    val at = lo - start
                    for (c in 0 until ch) {
                        val src = if (c == 0) left else right
                        val dst = input[c]
                        dst.fill(0f)
                        for (i in lo until hi) dst[at + i - lo] = ((src[i] - stats.mean) / stats.gain).toFloat()
                    }
                    val mag = spectro.forward(input)
                    val mixFlat = FloatArray(ch * seg)
                    for (c in 0 until ch) System.arraycopy(input[c], 0, mixFlat, c * seg, seg)

                    OnnxTensor.createTensor(env, FloatBuffer.wrap(mixFlat), longArrayOf(1, ch.toLong(), seg.toLong())).use { mixT ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(mag), longArrayOf(1, 2L * ch, freq.toLong(), fr.toLong())).use { magT ->
                            session.run(mapOf(mixName to mixT, magName to magT)).use { res ->
                                val specBuf = (res.get("spec").get() as OnnxTensor).floatBuffer
                                val wavBuf = (res.get("wav").get() as OnnxTensor).floatBuffer
                                val trim = delta / 2
                                val wTmp = FloatArray(seg)
                                owned.forEachIndexed { k, s ->
                                    val sb = specBuf.duplicate()
                                    sb.position(s * 2 * ch * freq * fr)
                                    sb.get(specTmp)
                                    val wave = spectro.inverse(specTmp, 0, ch)
                                    val wb = wavBuf.duplicate()
                                    for (c in 0 until ch) {
                                        wb.position((s * ch + c) * seg)
                                        wb.get(wTmp)
                                        val plane = k * ch + c
                                        acc.add(offset, plane, wave[c], wTmp, trim, length, weight)
                                    }
                                }
                                acc.addWeight(offset, length, weight)
                            }
                        }
                    }

                    val upTo = min(total, offset + stride)
                    acc.flush(upTo)?.let { block ->
                        val n = block[0].size
                        owned.forEachIndexed { k, s ->
                            val l = block[k * ch]
                            val r = if (ch > 1) block[k * ch + 1] else l
                            for (i in 0 until n) {
                                l[i] = (l[i] * stats.gain + stats.mean).toFloat()
                                if (ch > 1) r[i] = (r[i] * stats.gain + stats.mean).toFloat()
                            }
                            sink.write(s, l, r, n)
                        }
                    }
                    progress((index + 1f) / offsets.size)
                }
            }
        }
    }

    /** Somma pesata con sovrapposizione, finestra scorrevole di capacità fissa. */
    private class Accumulator(private val planes: Int, private val capacity: Int) {
        private val buf = Array(planes) { FloatArray(capacity) }
        private val w = FloatArray(capacity)
        private var base = 0

        fun add(offset: Int, plane: Int, inv: FloatArray, wav: FloatArray, trim: Int, n: Int, weight: FloatArray) {
            val start = offset - base
            check(start >= 0 && start + n <= capacity) { "blocco fuori dalla finestra" }
            val b = buf[plane]
            for (i in 0 until n) b[start + i] += (wav[trim + i] + inv[trim + i]) * weight[i]
        }

        fun addWeight(offset: Int, n: Int, weight: FloatArray) {
            val start = offset - base
            for (i in 0 until n) w[start + i] += weight[i]
        }

        fun flush(upTo: Int): Array<FloatArray>? {
            val n = upTo - base
            if (n <= 0) return null
            val out = Array(planes) { p -> FloatArray(n) { i -> buf[p][i] / w[i] } }
            val keep = capacity - n
            for (p in 0 until planes) {
                System.arraycopy(buf[p], n, buf[p], 0, keep)
                buf[p].fill(0f, keep, capacity)
            }
            System.arraycopy(w, n, w, 0, keep)
            w.fill(0f, keep, capacity)
            base = upTo
            return out
        }
    }

    companion object {
        /** Dissolvenza triangolare di demucs (transition_power = 1). */
        fun transitionWeight(segment: Int): FloatArray {
            val half = segment / 2
            val ramp = DoubleArray(segment) { if (it < half) (it + 1).toDouble() else (segment - it).toDouble() }
            val m = ramp.max()
            return FloatArray(segment) { (ramp[it] / m).toFloat() }
        }
    }
}
