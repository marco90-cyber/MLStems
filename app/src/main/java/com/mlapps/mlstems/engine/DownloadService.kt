package com.mlapps.mlstems.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mlapps.mlstems.data.ModelCatalog
import com.mlapps.mlstems.data.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object DownloadState {
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())   // id modello -> avanzamento
    val message = MutableStateFlow<String?>(null)
    val tick = MutableStateFlow(0)                                     // cambia a ogni download concluso
}

/** Foreground service: i download dei modelli continuano con l'app chiusa e lo schermo spento. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_MODEL)
        if (id == null) { stopSelf(); return START_NOT_STICKY }

        startForeground(2, notification("Download in corso…", 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wake == null) wake = acquireWakeLock(this, "download")
        if (id in DownloadState.downloads.value) return START_NOT_STICKY   // già in corso

        val model = ModelCatalog.byId(id)
        DownloadState.downloads.update { it + (id to 0f) }
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            var ok = false
            try {
                ModelManager(this@DownloadService).download(model) { p ->
                    DownloadState.downloads.update { it + (id to p) }
                    nm.notify(2, notification("${model.name} · ${(p * 100).toInt()}%", p))
                }
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadState.message.value = e.message
            } finally {
                DownloadState.downloads.update { it - id }
                DownloadState.tick.update { it + 1 }
                if (DownloadState.downloads.value.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            notifyResult(this@DownloadService, 4, if (ok) "${model.name} scaricato" else "Download di ${model.name} non riuscito")
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, progress: Float): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("download", "Download modelli", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, "download")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MLStems")
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODEL = "model"

        fun start(context: Context, modelId: String) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java).putExtra(EXTRA_MODEL, modelId)
            )
        }
    }
}
