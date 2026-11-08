package com.mlapps.mlstems.core

import java.io.OutputStream

/** Scrittore di bit big-endian, come richiede FLAC. */
internal class BitWriter {
    private var buf = ByteArray(1 shl 14)
    var size = 0
        private set
    private var acc = 0L
    private var nbits = 0

    private fun put(b: Int) {
        if (size == buf.size) buf = buf.copyOf(size * 2)
        buf[size++] = b.toByte()
    }

    fun bits(value: Long, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (value and ((1L shl n) - 1))
        nbits += n
        while (nbits >= 8) { put(((acc shr (nbits - 8)) and 0xFF).toInt()); nbits -= 8 }
        acc = acc and ((1L shl nbits) - 1)
    }

    fun bits(value: Int, n: Int) = bits(value.toLong(), n)

    fun unary(zeros: Int) {
        var z = zeros
        while (z >= 32) { bits(0, 32); z -= 32 }
        bits(1L, z + 1)
    }

    fun alignByte() { if (nbits > 0) bits(0, 8 - nbits) }

    fun bytes() = buf
    fun reset() { size = 0; acc = 0; nbits = 0 }
}

/**
 * Encoder FLAC lossless, 16 bit, stereo, blocchi da 4096 campioni.
 * Predittori fissi (ordine 0-4), codifica Rice a partizioni, scelta automatica
 * fra L/R, L/S, S/R, M/S. Il numero totale di campioni è noto in anticipo,
 * quindi l'intestazione si scrive subito e il file si produce in streaming.
 */
class FlacEncoder(
    private val out: OutputStream,
    private val sampleRate: Int,
    private val totalSamples: Long,
) : AutoCloseable {

    companion object {
        const val BLOCK = 4096
        private val CRC8 = IntArray(256).also { t ->
            for (i in 0 until 256) { var c = i; repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }; t[i] = c and 0xFF }
        }
        private val CRC16 = IntArray(256).also { t ->
            for (i in 0 until 256) { var c = i shl 8; repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }; t[i] = c and 0xFFFF }
        }
        fun crc8(b: ByteArray, len: Int): Int { var c = 0; for (i in 0 until len) c = CRC8[c xor (b[i].toInt() and 0xFF)]; return c }
        fun crc16(b: ByteArray, len: Int): Int { var c = 0; for (i in 0 until len) c = ((c shl 8) and 0xFFFF) xor CRC16[(c shr 8) xor (b[i].toInt() and 0xFF)]; return c }
    }

    private val left = IntArray(BLOCK)
    private val right = IntArray(BLOCK)
    private var fill = 0
    private var frameNo = 0L
    private var written = 0L
    private val bw = BitWriter()

    init {
        val h = BitWriter()
        h.bits(0x664C6143, 32)               // "fLaC"
        h.bits(1, 1); h.bits(0, 7); h.bits(34, 24) // ultimo blocco metadati: STREAMINFO
        h.bits(BLOCK, 16); h.bits(BLOCK, 16)
        h.bits(0, 24); h.bits(0, 24)         // dimensioni frame sconosciute
        h.bits(sampleRate.toLong(), 20); h.bits(1, 3) /* canali-1 */; h.bits(15, 5) /* bps-1 */
        h.bits(totalSamples, 36)
        repeat(4) { h.bits(0, 32) }          // MD5 non calcolato
        out.write(h.bytes(), 0, h.size)
    }

    /** Campioni 16 bit già in intervallo [-32768, 32767]. */
    fun write(l: ShortArray, r: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val m = minOf(BLOCK - fill, count - i)
            for (k in 0 until m) { left[fill + k] = l[i + k].toInt(); right[fill + k] = r[i + k].toInt() }
            fill += m; i += m
            if (fill == BLOCK) flush()
        }
    }

    fun write(l: FloatArray, r: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val m = minOf(BLOCK - fill, count - i)
            for (k in 0 until m) {
                left[fill + k] = (l[i + k] * 32767f).toInt().coerceIn(-32768, 32767)
                right[fill + k] = (r[i + k] * 32767f).toInt().coerceIn(-32768, 32767)
            }
            fill += m; i += m
            if (fill == BLOCK) flush()
        }
    }

    private fun flush() {
        val n = fill
        if (n == 0) return
        val mid = IntArray(n); val side = IntArray(n)
        for (i in 0 until n) { mid[i] = (left[i] + right[i]) shr 1; side[i] = left[i] - right[i] }
        val sets = arrayOf(left, right, mid, side)
        val bps = intArrayOf(16, 16, 16, 17)
        val encs = Array(4) { encodeSubframe(sets[it], n, bps[it]) }
        val costs = intArrayOf(
            encs[0].size + encs[1].size, encs[0].size + encs[3].size,
            encs[3].size + encs[1].size, encs[2].size + encs[3].size,
        )
        val best = costs.indices.minByOrNull { costs[it] }!!
        val (a, b, assign) = when (best) {
            0 -> Triple(0, 1, 1)             // L/R (assegnazione 1 = 2 canali indipendenti)
            1 -> Triple(0, 3, 8)             // sinistra/lato
            2 -> Triple(3, 1, 9)             // lato/destra
            else -> Triple(2, 3, 10)         // medio/lato
        }
        bw.reset()
        bw.bits(0xFFF8, 16)                  // sync + blocchi a dimensione fissa
        val fixedSize = n == BLOCK
        bw.bits(if (fixedSize) 12 else 7, 4)  // 4096 oppure 16 bit espliciti
        bw.bits(9, 4)                        // 44,1 kHz
        bw.bits(if (assign == 1) 1 else assign, 4)
        bw.bits(4, 3); bw.bits(0, 1)         // 16 bit per campione
        utf8(frameNo)
        if (!fixedSize) bw.bits(n - 1, 16)
        bw.alignByte()
        bw.bits(crc8(bw.bytes(), bw.size), 8)
        appendBits(encs[a]); appendBits(encs[b])
        bw.alignByte()
        bw.bits(crc16(bw.bytes(), bw.size), 16)
        out.write(bw.bytes(), 0, bw.size)
        written += n; frameNo++; fill = 0
    }

    private fun utf8(v: Long) {
        when {
            v < 0x80 -> bw.bits(v, 8)
            v < 0x800 -> { bw.bits(0xC0 or (v shr 6).toInt(), 8); bw.bits(0x80 or (v and 0x3F).toInt(), 8) }
            v < 0x10000 -> { bw.bits(0xE0 or (v shr 12).toInt(), 8); bw.bits(0x80 or ((v shr 6) and 0x3F).toInt(), 8); bw.bits(0x80 or (v and 0x3F).toInt(), 8) }
            else -> { bw.bits(0xF0 or (v shr 18).toInt(), 8); bw.bits(0x80 or ((v shr 12) and 0x3F).toInt(), 8); bw.bits(0x80 or ((v shr 6) and 0x3F).toInt(), 8); bw.bits(0x80 or (v and 0x3F).toInt(), 8) }
        }
    }

    /** Sottoframe già codificato come sequenza di bit (valori + lunghezze) per poterne confrontare il costo. */
    private class Enc(val size: Int, val writeTo: (BitWriter) -> Unit)

    private fun appendBits(e: Enc) = e.writeTo(bw)

    private fun encodeSubframe(x: IntArray, n: Int, bps: Int): Enc {
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) return Enc(8 + bps) { w -> w.bits(0, 8); w.bits(x[0], bps) }

        var bestOrder = -1; var bestBits = Int.MAX_VALUE; var bestRes: IntArray? = null
        var bestPart = 0; var bestK: IntArray? = null
        for (order in 0..4) {
            if (order >= n) break
            val res = IntArray(n - order)
            for (i in order until n) {
                res[i - order] = when (order) {
                    0 -> x[i]
                    1 -> x[i] - x[i - 1]
                    2 -> x[i] - 2 * x[i - 1] + x[i - 2]
                    3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
                    else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
                }
            }
            val (pbits, part, ks) = ricePlan(res, n, order)
            val total = 8 + order * bps + 2 + 4 + pbits
            if (total < bestBits) { bestBits = total; bestOrder = order; bestRes = res; bestPart = part; bestK = ks }
        }
        val verbatimBits = 8 + n * bps
        if (verbatimBits <= bestBits) {
            return Enc(verbatimBits) { w -> w.bits(0x02, 8); for (i in 0 until n) w.bits(x[i], bps) }
        }
        val order = bestOrder; val res = bestRes!!; val part = bestPart; val ks = bestK!!
        return Enc(bestBits) { w ->
            w.bits((8 + order) shl 1, 8)     // 0 + tipo 001ooo + nessun bit sprecato
            for (i in 0 until order) w.bits(x[i], bps)
            w.bits(0, 2); w.bits(part, 4)
            var pos = 0
            for (p in 0 until (1 shl part)) {
                val cnt = (n shr part) - (if (p == 0) order else 0)
                val k = ks[p]
                w.bits(k, 4)
                for (i in pos until pos + cnt) {
                    val r = res[i]; val u = (r shl 1) xor (r shr 31)
                    w.unary(u ushr k)
                    w.bits(u and ((1 shl k) - 1), k)
                }
                pos += cnt
            }
        }
    }

    private data class Plan(val bits: Int, val part: Int, val ks: IntArray)

    private fun ricePlan(res: IntArray, n: Int, order: Int): Plan {
        var best = Plan(Int.MAX_VALUE, 0, IntArray(1))
        for (part in 0..6) {
            if (n % (1 shl part) != 0) break
            if ((n shr part) <= order) break
            val parts = 1 shl part
            val ks = IntArray(parts)
            var total = 0
            var pos = 0
            for (p in 0 until parts) {
                val cnt = (n shr part) - (if (p == 0) order else 0)
                var bestK = 0; var bestB = Long.MAX_VALUE
                for (k in 0..14) {
                    var b = cnt.toLong() * (k + 1)
                    for (i in pos until pos + cnt) { val r = res[i]; b += ((r shl 1) xor (r shr 31)).toLong() and 0xFFFFFFFFL shr k }
                    if (b < bestB) { bestB = b; bestK = k }
                }
                ks[p] = bestK; total += bestB.toInt() + 4; pos += cnt
            }
            if (total < best.bits) best = Plan(total, part, ks)
        }
        return best
    }

    override fun close() {
        flush()
        out.flush()
        out.close()
    }
}
