package com.mlapps.mlstems.engine

import android.content.Context

/** Impostazioni di elaborazione dell'app. */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", 0)

    /** Formato in cui si salvano gli stem nella cartella dei progetti. */
    fun storeFormat(ctx: Context) = AudioFormat.of(prefs(ctx).getString("store_format", null), AudioFormat.MP3)
    fun setStoreFormat(ctx: Context, key: String) { prefs(ctx).edit().putString("store_format", key).apply() }

    /** Formato predefinito per "Esporta mix" / "Esporta stem". */
    fun exportFormat(ctx: Context) = AudioFormat.of(prefs(ctx).getString("export_format", null), AudioFormat.MP3)
    fun setExportFormat(ctx: Context, key: String) { prefs(ctx).edit().putString("export_format", key).apply() }


    val maxThreads: Int get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val defaultThreads: Int get() = maxThreads.coerceIn(2, 4)

    fun threads(ctx: Context): Int =
        ctx.getSharedPreferences("settings", 0).getInt("threads", defaultThreads).coerceIn(1, maxThreads)

    fun setThreads(ctx: Context, n: Int) {
        ctx.getSharedPreferences("settings", 0).edit().putInt("threads", n.coerceIn(1, maxThreads)).apply()
    }
}
