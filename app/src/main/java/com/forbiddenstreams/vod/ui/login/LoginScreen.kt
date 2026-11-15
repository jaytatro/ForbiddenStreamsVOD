package com.forbiddenstreams.vod.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forbiddenstreams.vod.AppContainer
import com.forbiddenstreams.vod.R
import com.forbiddenstreams.vod.appContainer
import com.forbiddenstreams.vod.data.Account
import com.forbiddenstreams.vod.data.XtreamClient
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.focusHighlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    var server by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun signIn(onSuccess: () -> Unit) {
        if (loading) return
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            error = "Fill in the server, username and password."
            return
        }
        val account = Account(
            server = XtreamClient.normalizeServer(server),
            username = username.trim(),
            password = password.trim(),
        )
        loading = true
        error = null
        viewModelScope.launch {
            try {
                XtreamClient(container.http, account).authenticate()
                container.store.saveAccount(account)
                container.activate(account)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Sign in failed"
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val container = LocalContext.current.appContainer()
    val vm: LoginViewModel = viewModel { LoginViewModel(container) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(Brand.Screen, Brand.Background), radius = 1600f),
            )
            .safeDrawingPadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Android TV (and landscape tablets): logo on the left, form on the right.
            if (maxWidth >= 720.dp && maxWidth > maxHeight) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 56.dp, vertical = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Logo(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        LoginForm(vm, onSignedIn, Modifier.widthIn(max = 460.dp))
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Logo(Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    LoginForm(vm, onSignedIn, Modifier.widthIn(max = 460.dp))
                }
            }
        }
    }
}

@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = "Forbidden Streams VOD",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, Brand.ToxicGreen.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
        )
    }
}

@Composable
private fun LoginForm(vm: LoginViewModel, onSignedIn: () -> Unit, modifier: Modifier = Modifier) {
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Brand.GlowGreen,
        focusedLabelColor = Brand.GlowGreen,
        cursorColor = Brand.GlowGreen,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Brand.ToxicGreen)) { append("Forbidden ") }
                withStyle(SpanStyle(color = Brand.Bone)) { append("Streams VOD") }
            },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
        )
        OutlinedTextField(
            value = vm.server,
            onValueChange = { vm.server = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://provider.example:8080") },
            singleLine = true,
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(firstField),
        )
        OutlinedTextField(
            value = vm.username,
            onValueChange = { vm.username = it },
            label = { Text("Username") },
            singleLine = true,
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.password,
            onValueChange = { vm.password = it },
            label = { Text("Password") },
            singleLine = true,
            colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.signIn(onSignedIn) }),
            modifier = Modifier.fillMaxWidth(),
        )
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { vm.signIn(onSignedIn) },
            enabled = !vm.loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .focusHighlight(RoundedCornerShape(26.dp)),
        ) {
            if (vm.loading) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Sign in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}
