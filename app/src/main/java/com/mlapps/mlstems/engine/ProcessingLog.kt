package com.mlapps.mlstems.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro delle elaborazioni: tempi, modello, thread, memoria, errori.
 * Serve a capire dove l'algoritmo perde tempo; si condivide dalla schermata Log.
 */
object ProcessingLog {
    private const val MAX_BYTES = 400_000
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)

    private fun file(ctx: Context) = File(ctx.filesDir, "processing-log.txt")

    @Synchronized
    fun add(ctx: Context, line: String) {
        val f = file(ctx)
        if (f.exists() && f.length() > MAX_BYTES) {          // tiene solo la parte più recente
            val t = f.readText()
            f.writeText(t.substring(t.length / 2).substringAfter('\n'))
        }
        f.appendText("${fmt.format(Date())}  $line\n")
    }

    fun read(ctx: Context): String = file(ctx).takeIf { it.exists() }?.readText().orEmpty()

    fun clear(ctx: Context) { file(ctx).delete() }

    fun fileFor(ctx: Context): File = file(ctx)

    /** Informazioni sul telefono e sulla memoria, in una riga. */
    fun device(ctx: Context): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val mb = 1024 * 1024
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "${Runtime.getRuntime().availableProcessors()} core, RAM ${mi.totalMem / mb} MB (libera ${mi.availMem / mb} MB), " +
            "heap max ${Runtime.getRuntime().maxMemory() / mb} MB"
    }

    /** Memoria usata adesso e picco del processo (VmHWM). */
    fun memory(): String {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val peak = runCatching {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM") }?.substringAfter(':')?.trim()
        }.getOrNull() ?: "?"
        return "heap ${(rt.totalMemory() - rt.freeMemory()) / mb} MB, nativa ${android.os.Debug.getNativeHeapAllocatedSize() / mb} MB, picco processo $peak"
    }
}
