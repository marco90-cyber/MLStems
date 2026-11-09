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

/** Registra i crash: quelli Java subito, quelli nativi / di memoria al prossimo avvio (Android 11+). */
object CrashLog {
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { ProcessingLog.add(app, "CRASH nel thread ${t.name}: ${android.util.Log.getStackTraceString(e).take(3000)}") }
            old?.uncaughtException(t, e)
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching { logPreviousExits(app) }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun logPreviousExits(ctx: Context) {
        val prefs = ctx.getSharedPreferences("crashlog", 0)
        val last = prefs.getLong("last", 0L)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8).filter { it.timestamp > last }
        for (x in exits.sortedBy { it.timestamp }) {
            val reason = when (x.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash Java"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash NATIVO"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "memoria esaurita (chiusa da Android)"
                android.app.ApplicationExitInfo.REASON_ANR -> "app bloccata (ANR)"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "segnale ${x.status}"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso eccessivo di risorse"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED, android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "chiusa dall'utente"
                else -> null
            } ?: continue                                    // uscite normali: non servono
            val trace = if (x.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == android.app.ApplicationExitInfo.REASON_ANR)
                runCatching { x.traceInputStream?.bufferedReader()?.use { it.readText() }?.take(2500) }.getOrNull() else null
            ProcessingLog.add(ctx, "Chiusura precedente (${SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(x.timestamp))}): $reason · ${x.description.orEmpty()}" +
                (if (trace != null) "\n$trace" else ""))
        }
        prefs.edit().putLong("last", System.currentTimeMillis()).apply()
    }
}
