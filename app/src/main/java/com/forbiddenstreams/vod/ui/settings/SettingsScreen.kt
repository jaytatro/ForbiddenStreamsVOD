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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

/** Where we are in a parental-controls PIN flow. */
sealed interface PinStep {
    data object Create : PinStep
    data class Confirm(val first: String) : PinStep
    data object VerifyToTurnOff : PinStep
    data object VerifyToChange : PinStep
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val updates = container.updates
    val parentalEnabled = container.store.parentalEnabled

    var pinStep by mutableStateOf<PinStep?>(null)
        private set
    var pinError by mutableStateOf<String?>(null)
        private set
    var parentalMessage by mutableStateOf<String?>(null)
        private set

    fun startPin(step: PinStep) {
        pinError = null
        parentalMessage = null
        pinStep = step
    }

    fun cancelPin() {
        pinStep = null
        pinError = null
    }

    fun onPinEntered(pin: String) {
        val step = pinStep ?: return
        viewModelScope.launch {
            when (step) {
                PinStep.Create -> {
                    pinError = null
                    pinStep = PinStep.Confirm(pin)
                }
                is PinStep.Confirm -> if (pin == step.first) {
                    container.store.setParentalPin(pin)
                    pinStep = null
                    pinError = null
                    parentalMessage = "Parental controls are on. 18+ content is hidden."
                } else {
                    pinError = "Those PINs didn't match. Start again."
                    pinStep = PinStep.Create
                }
                PinStep.VerifyToTurnOff -> if (container.store.checkParentalPin(pin)) {
                    container.store.clearParentalPin()
                    pinStep = null
                    pinError = null
                    parentalMessage = "Parental controls are off."
                } else {
                    pinError = "Wrong PIN. Try again."
                }
                PinStep.VerifyToChange -> if (container.store.checkParentalPin(pin)) {
                    pinError = null
                    pinStep = PinStep.Create
                } else {
                    pinError = "Wrong PIN. Try again."
                }
            }
        }
    }

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

            Section("Playback") {
                val forceHdr10 by container.store.forceHdr10.collectAsState(initial = container.forceHdr10)
                Text(
                    "The player adapts to each video: if a 4K Dolby Vision movie gets stuck, it automatically " +
                        "tries another way to decode it. Turn this on to always start Dolby Vision as HDR10.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .focusHighlight(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = forceHdr10,
                            role = Role.Switch,
                            onValueChange = { on ->
                                container.forceHdr10 = on
                                scope.launch { container.store.setForceHdr10(on) }
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Always play Dolby Vision as HDR10",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = forceHdr10,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Brand.ToxicGreen),
                    )
                }

                val matchFrameRate by container.store.matchFrameRate.collectAsState(initial = container.matchFrameRate)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .focusHighlight(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = matchFrameRate,
                            role = Role.Switch,
                            onValueChange = { on ->
                                container.matchFrameRate = on
                                scope.launch { container.store.setMatchFrameRate(on) }
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text("Match the TV's frame rate to the video", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Smoother motion: films switch the TV to 23.976 Hz, etc.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = matchFrameRate,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Brand.ToxicGreen),
                    )
                }
            }

            Section("Parental controls") {
                val enabled by vm.parentalEnabled.collectAsState(initial = false)
                Text(
                    if (enabled) "On: 18+ content is hidden." else "Off",
                    color = if (enabled) Brand.GlowGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Hides categories and titles marked 18+ or XXX (and adult categories) in Movies, " +
                        "TV Series, search and My List. A 4-digit PIN is needed to turn it off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                vm.parentalMessage?.let { Text(it, color = Brand.GlowGreen, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (enabled) {
                        OutlinedButton(
                            onClick = { vm.startPin(PinStep.VerifyToTurnOff) },
                            modifier = Modifier.focusHighlight(CircleShape),
                        ) { Text("Turn off") }
                        OutlinedButton(
                            onClick = { vm.startPin(PinStep.VerifyToChange) },
                            modifier = Modifier.focusHighlight(CircleShape),
                        ) { Text("Change PIN") }
                    } else {
                        Button(
                            onClick = { vm.startPin(PinStep.Create) },
                            modifier = Modifier.focusHighlight(CircleShape),
                        ) { Text("Turn on") }
                    }
                }
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

    vm.pinStep?.let { step ->
        val (title, message) = when (step) {
            PinStep.Create -> "Create a PIN" to "Choose a 4-digit PIN. You'll need it to turn parental controls off."
            is PinStep.Confirm -> "Confirm your PIN" to "Enter the same 4 digits again."
            PinStep.VerifyToTurnOff -> "Enter your PIN" to "Enter your PIN to turn parental controls off."
            PinStep.VerifyToChange -> "Enter your current PIN" to null
        }
        PinDialog(
            title = title,
            message = message,
            error = vm.pinError,
            onPin = vm::onPinEntered,
            onDismiss = vm::cancelPin,
        )
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
