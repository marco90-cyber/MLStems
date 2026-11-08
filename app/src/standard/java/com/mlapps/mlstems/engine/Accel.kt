package com.mlapps.mlstems.engine

import ai.onnxruntime.OrtSession
import android.content.Context

/** Build normale: solo CPU. */
object Accel {
    const val available = false
    fun configure(context: Context, options: OrtSession.SessionOptions) {}
}
