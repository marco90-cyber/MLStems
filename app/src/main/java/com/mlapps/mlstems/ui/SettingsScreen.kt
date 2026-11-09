package com.mlapps.mlstems.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Impostazioni: tema condiviso MLTheme, numero di thread, progetti da file. */
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var threads by remember { mutableStateOf(Settings.threads(ctx)) }

    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val msg = runCatching {
                val (p, existed) = ctx.contentResolver.openInputStream(uri)!!.use { vm.repo.importFrom(it) }
                if (existed) "“${p.title}” era già presente" else "Progetto “${p.title}” caricato"
            }.getOrElse { "File progetto non valido" }
            withContext(Dispatchers.Main) { vm.refreshProjects(); vm.message.value = msg }
        }
    }

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
        MLGroup("Progetti") {
            MLNavRow("Carica progetto da file", "Apri un file .mlstems salvato in precedenza") {
                opener.launch(arrayOf("*/*"))
            }
        }
    }
}
