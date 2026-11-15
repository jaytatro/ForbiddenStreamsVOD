package com.forbiddenstreams.vod.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forbiddenstreams.vod.AppContainer
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.UpdateInfo
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.ScreenHeader
import com.forbiddenstreams.vod.ui.focusHighlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: UpdateInfo) : UpdateState
    data class Downloading(val update: UpdateInfo, val progress: Float?) : UpdateState
    data class ReadyToInstall(val update: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String, val update: UpdateInfo? = null) : UpdateState
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val updates = container.updates

    var state by mutableStateOf<UpdateState>(
        container.availableUpdate?.let { UpdateState.Available(it) } ?: UpdateState.Idle,
    )
        private set

    private var job: Job? = null

    init {
        if (state is UpdateState.Idle) check()
    }

    fun check() {
        job?.cancel()
        state = UpdateState.Checking
        job = viewModelScope.launch {
            state = try {
                val update = updates.checkForUpdate()
                container.availableUpdate = update
                container.checkedForUpdates = true
                if (update != null) UpdateState.Available(update) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed("Couldn't check for updates: ${e.message ?: "network error"}")
            }
        }
    }

    fun download(update: UpdateInfo) {
        job?.cancel()
        state = UpdateState.Downloading(update, null)
        job = viewModelScope.launch {
            state = try {
                val apk = updates.download(update) { progress ->
                    viewModelScope.launch {
                        // Ignore late progress ticks once the download has finished or failed.
                        if (state is UpdateState.Downloading) state = UpdateState.Downloading(update, progress)
                    }
                }
                UpdateState.ReadyToInstall(update, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed("Download failed: ${e.message ?: "network error"}", update)
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val scope = rememberCoroutineScope()
    var installHint by remember { mutableStateOf<String?>(null) }

    fun launchInstall(apk: File) {
        val updates = vm.updates
        installHint = null
        try {
            if (!updates.canInstallPackages()) {
                installHint = "Allow Forbidden Streams VOD to install apps, then come back and press Install again."
                context.startActivity(updates.allowInstallsIntent())
            } else {
                context.startActivity(updates.installIntent(apk))
            }
        } catch (e: ActivityNotFoundException) {
            installHint = "On your TV, go to Settings > Apps > Special app access > Install unknown apps " +
                "(or Developer options on Fire TV), allow Forbidden Streams VOD, then press Install again."
        }
    }

    // Open the installer as soon as a download finishes.
    val ready = vm.state as? UpdateState.ReadyToInstall
    LaunchedEffect(ready?.apk) { ready?.let { launchInstall(it.apk) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("Settings", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("App updates") {
                Text(
                    "Installed version ${vm.updates.currentVersionName} (build ${vm.updates.currentBuild})",
                    style = MaterialTheme.typography.bodyLarge,
                )
                UpdateContent(
                    state = vm.state,
                    installHint = installHint,
                    onCheck = vm::check,
                    onDownload = vm::download,
                    onInstall = { launchInstall(it) },
                )
            }

            Section("Account") {
                val account = container.account
                Text("Server: ${account?.server ?: "—"}", style = MaterialTheme.typography.bodyLarge)
                Text("Username: ${account?.username ?: "—"}", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.signOut()
                            onSignedOut()
                        }
                    },
                    modifier = Modifier.focusHighlight(CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
                }
            }
        }
    }
}

@Composable
private fun UpdateContent(
    state: UpdateState,
    installHint: String?,
    onCheck: () -> Unit,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
) {
    val primary = remember { FocusRequester() }
    // Keep the D-pad on the main button as the state changes.
    LaunchedEffect(state::class) { runCatching { primary.requestFocus() } }
    val buttonModifier = Modifier
        .focusRequester(primary)
        .focusHighlight(CircleShape)

    when (state) {
        UpdateState.Idle, UpdateState.UpToDate -> {
            if (state == UpdateState.UpToDate) {
                Text("You're on the latest version.", color = Brand.GlowGreen, style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = onCheck, modifier = buttonModifier) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Check for updates")
            }
        }
        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text("Checking GitHub for a newer build…", style = MaterialTheme.typography.bodyLarge)
        }
        is UpdateState.Available -> {
            Text(
                "Build ${state.update.build} is available.",
                color = Brand.GlowGreen,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = { onDownload(state.update) }, modifier = buttonModifier) {
                Text("Download and install")
            }
        }
        is UpdateState.Downloading -> {
            val progress = state.progress
            Text(
                if (progress != null) "Downloading build ${state.update.build}… ${(progress * 100).toInt()}%"
                else "Downloading build ${state.update.build}…",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().widthIn(max = 520.dp))
            }
        }
        is UpdateState.ReadyToInstall -> {
            Text(
                "Build ${state.update.build} is downloaded. Confirm the install on the next screen.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { onInstall(state.apk) }, modifier = buttonModifier) {
                Text("Install")
            }
        }
        is UpdateState.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(
                onClick = { state.update?.let(onDownload) ?: onCheck() },
                modifier = buttonModifier,
            ) { Text("Try again") }
        }
    }
    if (installHint != null) {
        Text(installHint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Brand.ToxicGreen)
            content()
        }
    }
}
