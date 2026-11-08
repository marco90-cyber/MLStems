package com.mlapps.mlstems.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import com.mlapps.mlstems.data.ModelCatalog
import com.mlapps.mlstems.data.ModelManager
import com.mlapps.mlstems.data.Project
import com.mlapps.mlstems.data.ProjectRepository
import com.mlapps.mlstems.data.StemTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

sealed interface SeparationStatus {
    data object Idle : SeparationStatus
    data class Running(val title: String, val stage: String, val progress: Float) : SeparationStatus
    data class Done(val projectId: String) : SeparationStatus
    data class Error(val message: String) : SeparationStatus
}

object SeparationState {
    val status = MutableStateFlow<SeparationStatus>(SeparationStatus.Idle)
}

/** Foreground service: l'elaborazione continua anche con lo schermo spento. */
class SeparationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            SeparationState.status.value = SeparationStatus.Idle
            stopSelf()
            return START_NOT_STICKY
        }
        val uri = Uri.parse(intent!!.getStringExtra(EXTRA_URI)!!)
        val modelId = intent.getStringExtra(EXTRA_MODEL)!!
        startForeground(1, notification("Preparazione…", 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        job = scope.launch { run(uri, modelId) }
        return START_NOT_STICKY
    }

    private fun run(uri: Uri, modelId: String) {
        val state = SeparationState.status
        var title = "Brano"
        val wake = acquireWakeLock(this, "separation")   // CPU attiva anche a schermo spento
        try {
            val model = ModelCatalog.byId(modelId)
            val repo = ProjectRepository(this)
            title = displayName(uri)

            fun update(stage: String, p: Float) {
                state.value = SeparationStatus.Running(title, stage, p)
                getSystemService(NotificationManager::class.java).notify(1, notification("$title · $stage", p))
            }

            update("Controllo del brano", 0f)
            val hash = sha256(uri)
            repo.findByHash(hash, modelId)?.let {            // già elaborato: riapri il progetto
                state.value = SeparationStatus.Done(it.id); return
            }

            update("Lettura audio", 0f)
            val audio = AudioDecoder.decode(this, uri) { update("Lettura audio", it * 0.1f) }

            val modelFile = ModelManager(this).file(model)
            val stems = OnnxSeparator(model, modelFile).use { sep ->
                sep.separate(audio) { update("Separazione", 0.1f + it * 0.8f) }
            }

            val id = UUID.randomUUID().toString()
            val dir = repo.dir(id)
            val tracks = stems.mapIndexed { i, s ->
                update("Salvataggio", 0.9f + 0.1f * i / stems.size)
                val name = "stem_$i.wav"
                Wav.writeStem(dir.resolve(name), s.left, s.right)
                StemTrack(name = s.name, file = name)
            }
            repo.save(Project(id, title, hash, modelId, tracks, audio.frames * 1000L / SAMPLE_RATE))
            state.value = SeparationStatus.Done(id)
            notifyResult(this, 3, "$title: separazione completata")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            state.value = SeparationStatus.Error(e.message ?: e.javaClass.simpleName)
            notifyResult(this, 3, "$title: separazione non riuscita")
        } finally {
            if (wake.isHeld) wake.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun sha256(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).substringBeforeLast('.') else null
        } ?: "Brano"

    private fun notification(text: String, progress: Float): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Elaborazione", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MLStems")
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "separation"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MODEL = "model"
        const val ACTION_CANCEL = "cancel"

        fun start(context: Context, uri: Uri, modelId: String) {
            SeparationState.status.value = SeparationStatus.Running("Brano", "Avvio", 0f)
            context.startForegroundService(
                Intent(context, SeparationService::class.java)
                    .putExtra(EXTRA_URI, uri.toString())
                    .putExtra(EXTRA_MODEL, modelId)
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, SeparationService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
