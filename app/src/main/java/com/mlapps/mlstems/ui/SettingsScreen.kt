package com.mlapps.mlstems.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.marco.mltheme.MLGroup
import com.marco.mltheme.MLNavRow
import com.marco.mltheme.MLSettingsScaffold
import com.marco.mltheme.MLSliderRow
import com.marco.mltheme.MLThemeSettingsGroup
import com.mlapps.mlstems.engine.Settings
import kotlinx.coroutines.Dispatchers
import com.mlapps.mlstems.engine.AudioFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Impostazioni: tema condiviso MLTheme, numero di thread, progetti da file. */
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onLog: () -> Unit, onInfo: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var threads by remember { mutableStateOf(Settings.threads(ctx)) }
    var storeFmt by remember { mutableStateOf(Settings.storeFormat(ctx)) }
    var exportFmt by remember { mutableStateOf(Settings.exportFormat(ctx)) }

    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.loadProject(uri); onBack() }   // l'avanzamento si vede in Home
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setLibrary) }
    val library by vm.library.collectAsState()

    MLSettingsScaffold("Impostazioni", onBack) {
        MLThemeSettingsGroup()
        MLGroup("Elaborazione") {
            MLSliderRow(
                title = "Thread della CPU",
                valueLabel = if (threads == Settings.defaultThreads) "$threads (consigliato)" else "$threads",
                value = threads.toFloat(),
                range = 1f..Settings.maxThreads.toFloat(),
                steps = (Settings.maxThreads - 2).coerceAtLeast(0),
            ) { threads = it.toInt(); Settings.setThreads(ctx, threads) }
        }
        MLGroup("Formato audio") {
            MLNavRow("Stem salvati", "${storeFmt.label} · tocca per cambiare (vale per i nuovi brani)") {
                storeFmt = if (storeFmt == AudioFormat.MP3) AudioFormat.FLAC else AudioFormat.MP3
                Settings.setStoreFormat(ctx, storeFmt.key)
            }
            MLNavRow("Esportazione", "${exportFmt.label} · tocca per cambiare") {
                exportFmt = AudioFormat.entries[(exportFmt.ordinal + 1) % AudioFormat.entries.size]
                Settings.setExportFormat(ctx, exportFmt.key)
            }
        }
        MLGroup("Diagnostica") {
            MLNavRow("Log di elaborazione", "Tempi e memoria di ogni brano: condividilo per migliorare l'app") { onLog() }
            MLNavRow("Info e spazio", "Cache, licenze dei modelli") { onInfo() }
        }
        MLGroup("Progetti") {
            MLNavRow("Cartella dei progetti", library ?: "Non scelta: tocca per sceglierla") { tree.launch(null) }
            MLNavRow("Carica progetto da file", "Apri un file .mlstems salvato in precedenza") {
                opener.launch(arrayOf("*/*"))
            }
        }
    }
}
