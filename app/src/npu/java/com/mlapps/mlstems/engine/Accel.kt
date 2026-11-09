package com.mlapps.mlstems.engine

import ai.onnxruntime.OrtSession
import android.content.Context
import android.system.Os

/** Build sperimentale: provider Qualcomm QNN. Varianti: NPU Hexagon in fp16/fp32 e GPU Adreno. */
object Accel {
    const val available = true
    val variants = listOf("htp16", "htp32", "gpu")

    fun configure(context: Context, options: OrtSession.SessionOptions, variant: String = "htp16") {
        // il DSP cerca le librerie "Skel" in questo percorso
        Os.setenv("ADSP_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir, true)
        options.setSymbolicDimensionValue("num_splits", 1L)   // QNN non accetta forme dinamiche
        val o = mutableMapOf("backend_path" to if (variant == "gpu") "libQnnGpu.so" else "libQnnHtp.so")
        if (variant == "htp16") o["enable_htp_fp16_precision"] = "1"
        if (variant.startsWith("htp")) o["htp_performance_mode"] = "burst"
        options.addQnn(o)
    }
}
