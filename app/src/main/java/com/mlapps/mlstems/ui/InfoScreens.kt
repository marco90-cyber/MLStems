package com.mlapps.mlstems.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.marco.mltheme.MLGroup
import com.marco.mltheme.MLNavRow
import com.marco.mltheme.MLSettingsScaffold
import com.mlapps.mlstems.engine.ProcessingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Registro delle elaborazioni, da condividere per migliorare l'algoritmo. */
@Composable
fun LogScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { ProcessingLog.read(ctx) } }
    MLSettingsScaffold("Log di elaborazione", onBack) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = text.isNotEmpty(), onClick = {
                val f = ProcessingLog.fileFor(ctx)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Condividi il log"))
            }) { Text("Condividi") }
            OutlinedButton(enabled = text.isNotEmpty(), onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", text))
                vm.message.value = "Log copiato"
            }) { Text("Copia") }
            OutlinedButton(enabled = text.isNotEmpty(), onClick = { ProcessingLog.clear(ctx); text = "" }) { Text("Svuota") }
        }
        Text(
            text.ifEmpty { "Il log è vuoto: si riempie a ogni elaborazione." },
            Modifier.padding(16.dp).horizontalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

private fun mb(bytes: Long) = "%.0f MB".format(bytes / 1048576.0)

private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles().orEmpty().sumOf { dirSize(it) }

/** Info: spazio occupato, cache, licenze dei modelli. */
@Composable
fun InfoScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var cache by remember { mutableStateOf(0L) }
    var models by remember { mutableStateOf(0L) }
    var tracks by remember { mutableStateOf(0L) }
    fun refresh() {
        cache = dirSize(File(ctx.cacheDir, "stems"))
        models = dirSize(File(ctx.filesDir, "models"))
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { refresh(); tracks = vm.repo.usedBytes() }
    }
    MLSettingsScaffold("Info", onBack) {
        MLGroup("Spazio") {
            MLNavRow("Brani salvati", "${mb(tracks)} nella cartella dei progetti", trailing = null) {}
            MLNavRow("Modelli scaricati", mb(models), trailing = null) {}
            MLNavRow("Cache per l'ascolto", "${mb(cache)} · tocca per svuotarla", trailing = null) {
                File(ctx.cacheDir, "stems").deleteRecursively(); refresh()
                vm.message.value = "Cache svuotata"
            }
        }
        MLGroup("Modelli e licenze") {
            Text(
                "HT-Demucs è di Meta AI (Facebook Research); Spleeter è di Deezer. L'app usa versioni convertite in ONNX " +
                    "pubblicate da terzi (HRSadeghi/demucs-onnx e Best-Practice/spleeter-4stems-onnx), copiate nel repository dell'app " +
                    "per il download. Le licenze originali dei pesi possono porre limiti all'uso commerciale: controllale prima di " +
                    "usare i risultati a scopo di lavoro.",
                Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
