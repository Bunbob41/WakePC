package com.morgan.wakepc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val whitespace = Regex("\\s+")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WakeScreen(repository)
                }
            }
        }
    }
}

@Composable
private fun WakeScreen(repository: SettingsRepository) {
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val saved = repository.current()
        baseUrl = saved.baseUrl
        token = saved.token
    }

    // Pastes from chat often drag invisible extras into a single-line field
    // (newlines, a trailing "Token:" label), so keep only the plausible part:
    // the first whitespace-delimited chunk for the URL, the last for the token.
    fun settingsFromFields() = WakeSettings(
        baseUrl = baseUrl.trim().takeWhile { !it.isWhitespace() }.trimEnd('/'),
        token = token.trim().split(whitespace).last(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WakePC", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Point this at the wakepc service on your Pi, then add the " +
                "Wake PC tile to Quick Settings.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Pi base URL") },
            placeholder = { Text("http://100.x.y.z:8787") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                scope.launch {
                    val settings = settingsFromFields()
                    repository.save(settings)
                    baseUrl = settings.baseUrl
                    token = settings.token
                    message = "Saved."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = settingsFromFields().isConfigured,
                onClick = {
                    scope.launch {
                        val settings = settingsFromFields()
                        repository.save(settings)
                        baseUrl = settings.baseUrl
                        token = settings.token
                        message = "Sending wake…"
                        message = WakeApi.wake(settings).fold(
                            onSuccess = { "Magic packet sent." },
                            onFailure = { "Wake failed: ${it.message}" },
                        )
                    }
                },
            ) { Text("Wake PC") }

            OutlinedButton(
                enabled = settingsFromFields().isConfigured,
                onClick = {
                    scope.launch {
                        val settings = settingsFromFields()
                        repository.save(settings)
                        baseUrl = settings.baseUrl
                        token = settings.token
                        message = "Checking…"
                        message = WakeApi.status(settings).fold(
                            onSuccess = { awake -> if (awake) "PC is awake." else "PC is not responding to ping." },
                            onFailure = { "Status check failed: ${it.message}" },
                        )
                    }
                },
            ) { Text("Check status") }
        }

        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
