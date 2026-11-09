package com.mlapps.mlstems.engine

import android.content.Context

/** Impostazioni di elaborazione dell'app. */
object Settings {
    val maxThreads: Int get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val defaultThreads: Int get() = maxThreads.coerceIn(2, 4)

    fun threads(ctx: Context): Int =
        ctx.getSharedPreferences("settings", 0).getInt("threads", defaultThreads).coerceIn(1, maxThreads)

    fun setThreads(ctx: Context, n: Int) {
        ctx.getSharedPreferences("settings", 0).edit().putInt("threads", n.coerceIn(1, maxThreads)).apply()
    }
}
