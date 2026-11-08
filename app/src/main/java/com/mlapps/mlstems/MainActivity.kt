package com.mlapps.mlstems

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mlapps.mlstems.engine.SeparationService
import com.mlapps.mlstems.ui.AppViewModel
import com.mlapps.mlstems.ui.HomeScreen
import com.mlapps.mlstems.ui.MixerScreen
import com.mlapps.mlstems.ui.MLStemsTheme
import com.mlapps.mlstems.ui.ModelsScreen
import com.mlapps.mlstems.ui.NewTrackScreen
import com.mlapps.mlstems.ui.ProcessingScreen

sealed interface Screen {
    data object Home : Screen
    data object Models : Screen
    data class NewTrack(val uri: Uri) : Screen
    data object Processing : Screen
    data class Mixer(val projectId: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MLStemsTheme { App() } }
    }
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }
    BackHandler(screen != Screen.Home && screen != Screen.Processing) {
        vm.refreshProjects()
        screen = Screen.Home
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        val m = Modifier.padding(pad)
        when (val s = screen) {
            Screen.Home -> HomeScreen(vm, m,
                onPick = { screen = Screen.NewTrack(it) },
                onModels = { screen = Screen.Models },
                onOpen = { screen = Screen.Mixer(it) })
            Screen.Models -> ModelsScreen(vm, m, onBack = { screen = Screen.Home })
            is Screen.NewTrack -> {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                NewTrackScreen(vm, s.uri, m, onBack = { screen = Screen.Home }, onModels = { screen = Screen.Models },
                    onStart = { modelId ->
                        SeparationService.start(ctx, s.uri, modelId)
                        screen = Screen.Processing
                    })
            }
            Screen.Processing -> ProcessingScreen(m,
                onDone = { vm.refreshProjects(); screen = Screen.Mixer(it) },
                onClose = { screen = Screen.Home })
            is Screen.Mixer -> MixerScreen(vm, s.projectId, m, onBack = { vm.refreshProjects(); screen = Screen.Home })
        }
    }
}
