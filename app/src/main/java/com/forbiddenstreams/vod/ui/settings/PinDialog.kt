package com.forbiddenstreams.vod.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.forbiddenstreams.vod.ui.Brand
import com.forbiddenstreams.vod.ui.focusHighlight

private const val PIN_LENGTH = 4

/**
 * 4-digit PIN entry built for a TV remote: an on-screen keypad you move around with the
 * D-pad, and the remote's number keys work too. [onPin] fires once 4 digits are entered.
 */
@Composable
fun PinDialog(
    title: String,
    message: String?,
    error: String?,
    onPin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember(title) { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { firstKey.requestFocus() } }

    fun press(digit: Char) {
        if (pin.length >= PIN_LENGTH) return
        pin += digit
        if (pin.length == PIN_LENGTH) {
            val entered = pin
            pin = ""
            onPin(entered)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(min = 320.dp, max = 420.dp)
                .onPreviewKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    val digit = when (code) {
                        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_0)
                        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
                            '0' + (code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                        else -> null
                    }
                    when {
                        digit == null -> false
                        event.type == KeyEventType.KeyDown -> {
                            press(digit)
                            true
                        }
                        else -> true
                    }
                },
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Brand.ToxicGreen,
                    textAlign = TextAlign.Center,
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                // Entered digits as dots
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(PIN_LENGTH) { i ->
                        val filled = i < pin.length
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(if (filled) Brand.GlowGreen else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .border(2.dp, Brand.GlowGreen.copy(alpha = 0.6f), CircleShape),
                        )
                    }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                // Keypad
                val keys = listOf("123", "456", "789", "<0>")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    keys.forEach { rowKeys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowKeys.forEach { key ->
                                val modifier = Modifier
                                    .size(width = 76.dp, height = 56.dp)
                                    .then(if (key == '1') Modifier.focusRequester(firstKey) else Modifier)
                                    .focusHighlight(RoundedCornerShape(16.dp))
                                when (key) {
                                    '<' -> FilledTonalButton(
                                        onClick = { pin = pin.dropLast(1) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = modifier,
                                    ) { Text("⌫", style = MaterialTheme.typography.titleLarge) }
                                    '>' -> FilledTonalButton(
                                        onClick = { pin = "" },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = modifier,
                                    ) { Text("Clear", style = MaterialTheme.typography.labelLarge) }
                                    else -> FilledTonalButton(
                                        onClick = { press(key) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = modifier,
                                    ) { Text(key.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(CircleShape)) { Text("Cancel") }
            }
        }
    }
}
