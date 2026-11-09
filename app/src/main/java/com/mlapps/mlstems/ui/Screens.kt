package com.mlapps.mlstems.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height as hgt
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.mlapps.mlstems.data.ModelCatalog
import com.mlapps.mlstems.data.Project
import com.mlapps.mlstems.data.StemModel
import com.mlapps.mlstems.engine.SeparationService
import com.mlapps.mlstems.engine.SeparationState
import com.mlapps.mlstems.engine.SeparationStatus
import com.mlapps.mlstems.player.Exporter
import com.mlapps.mlstems.player.MultiStemPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
        },
        actions = { actions() },
    )
}

/** Icona pausa disegnata a mano: evita la libreria icone estesa (decine di MB). */
private val PauseIcon: ImageVector = ImageVector.Builder(
    name = "Pause", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineToRelative(-14f); horizontalLineToRelative(-4f); close()
    moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineToRelative(-14f); horizontalLineToRelative(-4f); close()
}.build()

private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

// ───────────────────────── Home: elenco progetti ─────────────────────────

@Composable
fun HomeScreen(vm: AppViewModel, modifier: Modifier, onPick: (Uri) -> Unit,  onModels: () -> Unit, onSettings: () -> Unit, onOpen: (String) -> Unit) {
    val projects by vm.projects.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPick) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by vm.library.collectAsState()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setLibrary) }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::loadProject) }
    val loading by vm.loading.collectAsState()
    var toDelete by remember { mutableStateOf<com.mlapps.mlstems.data.Project?>(null) }
    toDelete?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare “${p.title}”?") },
            text = {
                Text("La cartella “${p.folder}” con gli stem e il progetto verrà cancellata. Se vuoi tenerne una copia, usa prima “Salva progetto” nel mixer.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.deleteProject(p); toDelete = null }) { Text("Elimina") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }

    Column(modifier.fillMaxSize()) {
        Bar("MLStems", null) {
            androidx.compose.material3.TextButton(onClick = onModels) { Text("Modelli") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Impostazioni") }
        }
        val prepNow by vm.preparing.collectAsState()
        prepNow?.let { pr ->
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Preparo “${pr.title}” per l'ascolto", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { pr.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(pr.progress * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { vm.cancelPrepare() }) { Text("Annulla") }
                    }
                }
            }
        }
        loading?.let { l ->
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(l.label, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { l.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(l.progress * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { vm.cancelLoading() }) { Text("Annulla") }
                    }
                }
            }
        }
        if (library == null) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Scegli dove salvare i brani", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vai in Download, crea o scegli la cartella MLStems e tocca “Usa questa cartella”. Gli stem e i progetti staranno lì, uno per cartella.",
                        Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { treePicker.launch(null) }) { Text("Scegli la cartella") }
                }
            }
        }
        if (projects.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp).weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Voce", "Batteria", "Basso", "Chitarra", "Pianoforte", "Altro").forEachIndexed { i, n ->
                        androidx.compose.foundation.layout.Box(
                            Modifier.width(10.dp).hgt(listOf(36, 64, 48, 72, 44, 56)[i].dp)
                                .background(stemColor(n), androidx.compose.foundation.shape.RoundedCornerShape(50)),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Nessun brano ancora", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Tocca “Nuovo brano” per separare voce, batteria, basso e altro.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects, key = { it.id }) { p ->
                Card(
                    onClick = { onOpen(p.id) }, Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                "${ModelCatalog.all.firstOrNull { it.id == p.modelId }?.name ?: p.modelId} · ${fmt(p.durationMs)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.separateMs > 0) Text(
                                "Elaborato in ${fmt(p.separateMs)} · ×${"%.1f".format(p.realtimeFactor)} tempo reale · ${p.accel}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                p.stems.forEach { androidx.compose.foundation.layout.Box(
                                    Modifier.width(22.dp).hgt(6.dp).background(stemColor(it.name), androidx.compose.foundation.shape.RoundedCornerShape(50))) }
                            }
                        }
                        IconButton(onClick = { toDelete = p }) { Icon(Icons.Default.Delete, "Elimina") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(enabled = loading == null, onClick = { projectPicker.launch(arrayOf("*/*")) }) { Text("Carica un progetto") }
        }
        ExtendedFloatingActionButton(
            onClick = { if (library == null) treePicker.launch(null) else picker.launch(arrayOf("audio/*")) },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Nuovo brano") },
            modifier = Modifier.padding(16.dp).align(Alignment.End),
        )
    }
}

// ───────────────────────── Modelli ─────────────────────────

@Composable
fun ModelsScreen(vm: AppViewModel, modifier: Modifier, onBack: () -> Unit) {
    val installed by vm.installed.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val revision by vm.revision.collectAsState()
    var importing by remember { mutableStateOf<StemModel?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importing?.let { m -> uri?.let { vm.importModel(m, it) } }
        importing = null
    }

    Column(modifier.fillMaxSize()) {
        Bar("Modelli", onBack)
        Text(
            "Dal più veloce al più qualitativo. I modelli si scaricano una volta e restano sul dispositivo.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (com.mlapps.mlstems.engine.Accel.available) item { NpuPanel(vm) }
            items(ModelCatalog.all, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (m.id in installed) Pill("Installato")
                        }
                        Text(m.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("${m.stems.size} stem"); Pill("~${m.sizeMb} MB")
                        }
                        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Meter("Velocità", m.speed, color = MaterialTheme.colorScheme.tertiary)
                            Meter("Qualità", m.quality)
                        }
                        val p = downloads[m.id]
                        val downloadable = revision >= 0 && vm.models.isDownloadable(m)
                        val update = revision >= 0 && vm.models.updateAvailable(m)
                        when {
                            p != null -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            m.id in installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (update) Button(onClick = { vm.download(m) }) { Text("Aggiorna") }
                                OutlinedButton(onClick = { vm.deleteModel(m) }) { Text("Elimina") }
                            }
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.download(m) }, enabled = downloadable) {
                                    Text(if (downloadable) "Scarica" else "Non ancora disponibile")
                                }
                                FilledTonalButton(onClick = { importing = m; picker.launch(arrayOf("*/*")) }) { Text("Importa file") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Nuovo brano: scelta modello ─────────────────────────

@Composable
fun NewTrackScreen(vm: AppViewModel, uri: Uri, modifier: Modifier, onBack: () -> Unit, onModels: () -> Unit, onStart: (String, Boolean) -> Unit) {
    val installed by vm.installed.collectAsState()
    val available = ModelCatalog.all.filter { it.id in installed }
    var selected by remember(installed) { mutableStateOf(available.firstOrNull()?.id) }
    var two by remember { mutableStateOf(false) }
    val canTwo = available.firstOrNull { it.id == selected }?.family == com.mlapps.mlstems.data.Family.SPLEETER4
    val ctx = LocalContext.current
    var durationMs by remember(uri) { mutableStateOf(0L) }
    LaunchedEffect(uri) {
        durationMs = withContext(Dispatchers.IO) {
            runCatching {
                val r = android.media.MediaMetadataRetriever()
                try { r.setDataSource(ctx, uri); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
                finally { r.release() }
            }.getOrDefault(0L)
        }
    }
    val long = durationMs > 6 * 60_000L

    Column(modifier.fillMaxSize()) {
        Bar("Nuovo brano", onBack)
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            if (long) Card(Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Brano lungo: ${durationMs / 60000} min", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Oltre i 6 minuti l'elaborazione richiede molto tempo e molta memoria, e potrebbe non riuscire. " +
                            "Per brani lunghi conviene un modello veloce (Spleeter) e tenere il telefono in carica.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text("Scegli il modello", style = MaterialTheme.typography.titleMedium)
            if (available.isEmpty()) {
                Text("Nessun modello installato.", Modifier.padding(vertical = 8.dp))
                Button(onClick = onModels) { Text("Vai ai modelli") }
            }
            available.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == m.id, onClick = { selected = m.id })
                    Column {
                        Text(m.name)
                        Text("${m.stems.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (canTwo) {
                Text("Quanti stem", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !two, onClick = { two = false })
                    Column { Text("4 stem"); Text("Voce, batteria, basso, altro", style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = two, onClick = { two = true })
                    Column { Text("2 stem · circa 4 volte più veloce"); Text("Voce e strumentale", style = MaterialTheme.typography.bodySmall) }
                }
            } else if (selected != null) {
                Text("Con i modelli Demucs gli stem sono sempre quelli del modello: farne meno non fa risparmiare tempo.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onStart(selected!!, canTwo && two) }, enabled = selected != null) { Text(if (long) "Avvia comunque" else "Avvia separazione") }
            Text(
                "Se hai già elaborato questo brano con lo stesso modello, il progetto salvato si apre subito.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ───────────────────────── Elaborazione ─────────────────────────

@Composable
fun ProcessingScreen(modifier: Modifier, onDone: (String) -> Unit, onClose: () -> Unit) {
    val status by SeparationState.status.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(status) {
        (status as? SeparationStatus.Done)?.let { onDone(it.projectId) }
    }
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        when (val s = status) {
            is SeparationStatus.Running -> {
                Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                Text(s.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(s.stage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                Text("Puoi spegnere lo schermo: l'elaborazione continua.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { SeparationService.cancel(ctx); onClose() }, modifier = Modifier.padding(top = 16.dp)) { Text("Annulla") }
            }
            is SeparationStatus.Error -> {
                Text("Errore", style = MaterialTheme.typography.titleMedium)
                Text(s.message, Modifier.padding(vertical = 8.dp))
                Button(onClick = onClose) { Text("Chiudi") }
            }
            else -> CircularProgressIndicator()
        }
    }
}

// ───────────────────────── Mixer / player ─────────────────────────

@Composable
fun MixerScreen(vm: AppViewModel, projectId: String, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(projectId) {
        vm.watching = projectId
        vm.prepare(projectId)
        onDispose { if (vm.watching == projectId) vm.watching = null }
    }
    val preparedMap by vm.prepared.collectAsState()
    val preparing by vm.preparing.collectAsState()
    val failedSet by vm.prepFailed.collectAsState()
    val failed = projectId in failedSet
    val done = preparedMap[projectId]
    val prep = preparing?.takeIf { it.projectId == projectId }?.progress ?: 0f
    val wavs = done?.files
    val uris = done?.uris ?: emptyList()
    val ready = wavs
    if (failed) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("I file di questo brano non ci sono più nella cartella.", style = MaterialTheme.typography.titleMedium)
            Text("Sono stati spostati o cancellati. Puoi eliminare il brano dall'elenco.", Modifier.padding(top = 8.dp))
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Indietro") }
        }
    } else if (ready == null) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Preparo le tracce per l'ascolto…", Modifier.padding(top = 16.dp))
            LinearProgressIndicator(progress = { prep }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("${(prep * 100).toInt()}%", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Text("Puoi uscire: la preparazione continua in background e ti avviso quando è pronto.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) { Text("Continua in background") }
                OutlinedButton(onClick = { vm.cancelPrepare(); onBack() }) { Text("Annulla") }
            }
        }
    } else MixerContent(vm, projectId, ready, uris, modifier, onBack)
}

@Composable
private fun MixerContent(vm: AppViewModel, projectId: String, files: List<java.io.File>, stemUris: List<android.net.Uri>, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = remember(projectId) { vm.repo.load(projectId) }
    val player = remember(projectId) {
        MultiStemPlayer(files).also { p ->
            project.stems.forEachIndexed { i, t -> p.gains[i] = t.volume; p.muted[i] = t.muted; p.solo[i] = t.solo }
        }
    }
    var tracks by remember { mutableStateOf(project.stems) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportLabel by remember { mutableStateOf("") }
    val exportCancel = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun persist() = vm.saveProject(project.copy(stems = tracks))
    DisposableEffect(projectId) { onDispose { player.release(); persist() } }
    LaunchedEffect(projectId) {
        while (true) { pos = player.positionMs; playing = player.isPlaying; delay(150) }
    }

    Column(modifier.fillMaxSize()) {
        Bar(project.title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (project.separateMs > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Elaborato in ${fmt(project.separateMs)}"); Pill("×${"%.1f".format(project.realtimeFactor)} tempo reale"); Pill(project.accel)
            }
            tracks.forEachIndexed { i, t ->
                val c = stemColor(t.name)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                  Row(Modifier.height(IntrinsicSize.Min)) {
                    ColorStripe(c)
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Slider(colors = SliderDefaults.colors(thumbColor = c, activeTrackColor = c), value = t.volume, onValueChange = { v ->
                                player.gains[i] = v
                                tracks = tracks.toMutableList().also { it[i] = t.copy(volume = v) }
                            }, valueRange = 0f..1.5f)
                        }
                        FilledIconToggleButton(checked = t.muted, onCheckedChange = { c ->
                            player.muted[i] = c
                            tracks = tracks.toMutableList().also { it[i] = t.copy(muted = c) }
                        }) { Text("M") }
                        FilledIconToggleButton(checked = t.solo, onCheckedChange = { c ->
                            player.solo[i] = c
                            tracks = tracks.toMutableList().also { it[i] = t.copy(solo = c) }
                        }) { Text("S") }
                    }
                  }
                }
            }
            val fmt = com.mlapps.mlstems.engine.Settings.exportFormat(ctx)
            fun runExport(label: String, done: String, job: (onProgress: (Float) -> Unit, cancelled: () -> Boolean) -> Unit) {
                exporting = true; exportProgress = 0f; exportLabel = label; exportCancel.set(false)
                scope.launch(Dispatchers.IO) {
                    val msg = try {
                        job({ exportProgress = it }, { exportCancel.get() }); done
                    } catch (e: com.mlapps.mlstems.player.ExportCancelled) {
                        "Esportazione annullata"
                    } catch (e: Exception) {
                        com.mlapps.mlstems.engine.ProcessingLog.add(ctx, "Errore esportazione: ${e.message}")
                        "Esportazione non riuscita"
                    }
                    withContext(Dispatchers.Main) { exporting = false; vm.message.value = msg }
                }
            }
            if (exporting) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(exportLabel, style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { exportProgress }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { exportCancel.set(true) }) { Text("Annulla") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !exporting, onClick = {
                    runExport("Esporto il mix (${fmt.label})", "Mix salvato in Download/MLStems") { p, c ->
                        Exporter.exportMix(ctx, project.title, files, player.gains, player.muted, player.solo, fmt, p, c)
                    }
                }) { Text("Esporta mix") }
                OutlinedButton(enabled = !exporting, onClick = {
                    runExport("Esporto gli stem (${fmt.label})", "Stem salvati in Download/MLStems") { p, c ->
                        tracks.forEachIndexed { i, t ->
                            Exporter.exportStem(ctx, project.title, t.name, files[i], fmt, { p((i + it) / tracks.size) }, c)
                        }
                    }
                }) { Text("Esporta stem") }
            }
            Row {
                val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    if (uri != null) {
                        exporting = true
                        scope.launch(Dispatchers.IO) {
                            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { vm.repo.exportTo(projectId, it) } }.isSuccess
                            withContext(Dispatchers.Main) { exporting = false; vm.message.value = if (ok) "Progetto salvato nel file" else "Salvataggio non riuscito" }
                        }
                    }
                }
                OutlinedButton(enabled = !exporting, onClick = { saver.launch("${project.title}.mlstems") }) { Text("Salva progetto") }
            }
        }
        // trasporto
        Surface(tonalElevation = 3.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Slider(value = pos.toFloat(), onValueChange = { player.seekTo(it.toLong()); pos = it.toLong() },
                valueRange = 0f..player.durationMs.toFloat().coerceAtLeast(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(pos), Modifier.weight(1f))
                FilledIconButton(onClick = { if (playing) player.pause() else player.play() }, modifier = Modifier.size(56.dp)) {
                    Icon(if (playing) PauseIcon else Icons.Default.PlayArrow, if (playing) "Pausa" else "Riproduci")
                }
                Text(fmt(player.durationMs), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        }
    }
}


// ───────────────────────── NPU sperimentale ─────────────────────────

@Composable
private fun NpuPanel(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var on by remember { mutableStateOf(prefs.getBoolean("npu", false)) }
    var running by remember { mutableStateOf(false) }
    val lines = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NPU sperimentale", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; prefs.edit().putBoolean("npu", it).apply() })
            }
            Text(
                "Prova NPU Hexagon e GPU Qualcomm per Spleeter 4 stem. Il test sceglie da solo la variante più veloce; se nessuna batte la CPU, resta la CPU.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
            )
            FilledTonalButton(enabled = !running, onClick = {
                running = true; lines.clear(); lines += "Test in corso…"
                scope.launch(Dispatchers.IO) {
                    val f = vm.models.modelsDir.resolve("spleeter4_vocals.onnx")
                    com.mlapps.mlstems.engine.NpuBenchmark.run(ctx, f) { l -> scope.launch(Dispatchers.Main) { lines += l } }
                    withContext(Dispatchers.Main) { running = false }
                }
            }) { Text(if (running) "Test in corso…" else "Esegui test CPU / NPU") }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
