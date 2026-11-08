package com.mlapps.mlstems.engine

import ai.onnxruntime.OrtSession
import android.content.Context
import android.system.Os

/** Build sperimentale: provider Qualcomm QNN sul backend HTP (NPU Hexagon). */
object Accel {
    const val available = true

    fun configure(context: Context, options: OrtSession.SessionOptions) {
        // il DSP cerca le librerie "Skel" in questo percorso
        Os.setenv("ADSP_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir, true)
        options.setSymbolicDimensionValue("num_splits", 1L)   // QNN non accetta forme dinamiche
        options.addQnn(
            mapOf(
                "backend_path" to "libQnnHtp.so",
                "enable_htp_fp16_precision" to "1",
                "htp_performance_mode" to "burst",
            ),
        )
    }
}
