package com.mlapps.mlstems.engine

import ai.onnxruntime.OrtSession
import android.content.Context

/** Build normale: solo CPU. */
object Accel {
    const val available = false
    val variants: List<String> = emptyList()
    fun configure(context: Context, options: OrtSession.SessionOptions, variant: String = "") {}
}
