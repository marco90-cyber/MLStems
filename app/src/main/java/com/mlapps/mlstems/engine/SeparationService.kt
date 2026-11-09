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
import com.mlapps.mlstems.core.DemucsConfig
import com.mlapps.mlstems.core.DemucsEngine
import com.mlapps.mlstems.core.SpleeterEngine
import com.mlapps.mlstems.core.StemEngine
import com.mlapps.mlstems.core.StemSink
import com.mlapps.mlstems.data.Family
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
            runId++                                  // l'elaborazione in corso se ne accorge e si ferma
            job?.cancel()
            SeparationState.status.value = SeparationStatus.Idle
            stopSelf()
            return START_NOT_STICKY
        }
        val uri = Uri.parse(intent!!.getStringExtra(EXTRA_URI)!!)
        val modelId = intent.getStringExtra(EXTRA_MODEL)!!
        startForeground(1, notification("Preparazione…", 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val mine = ++runId
        job = scope.launch { run(uri, modelId, mine) }
        return START_NOT_STICKY
    }

    private fun run(uri: Uri, modelId: String, mine: Int) {
        val state = SeparationState.status
        var title = "Brano"
        val wake = acquireWakeLock(this, "separation")   // CPU attiva anche a schermo spento
        try {
            val model = ModelCatalog.byId(modelId)
            val repo = ProjectRepository(this)
            check(repo.libraryName() != null) { "Scegli prima la cartella dei progetti (Impostazioni)" }
            title = displayName(uri)

            fun update(stage: String, p: Float) {
                if (mine != runId) throw kotlinx.coroutines.CancellationException("annullato")
                state.value = SeparationStatus.Running(title, stage, p)
                getSystemService(NotificationManager::class.java).notify(1, notification("$title · $stage", p))
            }

            update("Controllo del brano", 0f)
            val hash = sha256(uri)
            repo.findByHash(hash, modelId)?.let {            // già elaborato: riapri il progetto
                state.value = SeparationStatus.Done(it.id); return
            }

            ProcessingLog.add(this, "──── Nuova elaborazione: \"$title\" con $modelId")
            ProcessingLog.add(this, "App ${appVersion()} · ${ProcessingLog.device(this)}")
            update("Lettura audio", 0f)
            val t0 = System.currentTimeMillis()
            val audio = AudioDecoder.decode(this, uri) { update("Lettura audio", it * 0.1f) }
            val decodeMs = System.currentTimeMillis() - t0
            ProcessingLog.add(this, "Lettura: ${audio.frames * 1000L / SAMPLE_RATE / 1000} s di audio in $decodeMs ms · ${ProcessingLog.memory()}")

            val mm = ModelManager(this)
            check(mm.isInstalled(model)) { "Modello non installato" }
            val threads = Settings.threads(this)
            ProcessingLog.add(this, "Modello ${model.name} (${model.family}), thread $threads, acceleratore ${if (Accel.available) "disponibile" else "assente"}")
            // NPU sperimentale (solo build NPU, attivata dall'utente, solo Spleeter): se non parte si torna alla CPU
            val prefs = getSharedPreferences("settings", 0)
            val variant = prefs.getString("npu_variant", "") ?: ""
            var useNpu = Accel.available && model.family == Family.SPLEETER4 && prefs.getBoolean("npu", false) && variant.isNotEmpty()
            if (useNpu) {
                update("Provo l'acceleratore", 0.1f)
                useNpu = NpuBenchmark.probe(this, mm.modelsDir.resolve("spleeter4_vocals.onnx"), variant) == null
            }
            val engine: StemEngine = when (model.family) {
                Family.DEMUCS -> DemucsEngine(DemucsConfig.load(mm.configFile(model)), threads)
                Family.SPLEETER4 -> SpleeterEngine(mm.modelsDir, threads, configure = { if (useNpu) Accel.configure(this, it, variant) })
            }

            val folder = repo.newFolder(title)
            val tSep = System.currentTimeMillis()
            val labels = engine.sources.map { stemLabel(it) }
            val fmt = Settings.storeFormat(this)
            ProcessingLog.add(this, "Stem salvati in ${fmt.label}")
            val docs = labels.map { repo.newFile(folder, "$it.${fmt.ext}", fmt.mime) }
            val cacheDir = java.io.File(cacheDir, "stems/${folder.name}")
            val writers = docs.mapIndexed { i, d ->
                StemWriter(contentResolver.openOutputStream(d.uri)!!, audio.frames, fmt, java.io.File(cacheDir, "${labels[i]}.wav"))
            }
            try {
                val sink = object : StemSink {
                    override fun write(stem: Int, left: FloatArray, right: FloatArray, count: Int) =
                        writers[stem].write(left, right, count)
                }
                var nextMark = 0.25f
                engine.separate(audio.left, audio.right, sink) {
                    update("Separazione", 0.1f + it * 0.9f)
                    if (it >= nextMark) {
                        ProcessingLog.add(this, "  ${(it * 100).toInt()}% dopo ${(System.currentTimeMillis() - tSep) / 1000.0} s · ${ProcessingLog.memory()}")
                        nextMark += 0.25f
                    }
                }
            } catch (e: Throwable) {
                writers.forEach { it.abort() }
                cacheDir.deleteRecursively()
                repo.deleteFolder(folder)
                throw e
            }
            writers.forEach { it.close() }
            val tracks = labels.mapIndexed { i, name -> StemTrack(name = name, file = docs[i].name ?: "$name.${fmt.ext}") }
            val saved = repo.finish(folder, Project(folder.name!!, title, hash, modelId, tracks, audio.frames * 1000L / SAMPLE_RATE,
                decodeMs = decodeMs, separateMs = System.currentTimeMillis() - tSep, accel = if (useNpu) "NPU" else "CPU"))
            val id = saved.id
            ProcessingLog.add(this, "Fine: separazione ${saved.separateMs} ms = ×${"%.2f".format(saved.realtimeFactor)} tempo reale (${saved.accel}) · ${ProcessingLog.memory()}")
            state.value = SeparationStatus.Done(id)
            notifyResult(this, 3, "$title: separazione completata")
        } catch (e: kotlinx.coroutines.CancellationException) {
            ProcessingLog.add(this, "Annullata dall'utente")
            throw e
        } catch (e: Throwable) {
            ProcessingLog.add(this, "ERRORE: ${e.stackTraceToString().take(2500)}")
            state.value = SeparationStatus.Error(e.message ?: e.javaClass.simpleName)
            notifyResult(this, 3, "$title: separazione non riuscita")
        } finally {
            if (wake.isHeld) wake.release()
            if (mine == runId) {                  // se nel frattempo ne è partita un'altra, non fermare il servizio
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

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
        @Volatile private var runId = 0
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
