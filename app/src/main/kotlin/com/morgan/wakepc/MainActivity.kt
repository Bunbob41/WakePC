package com.morgan.wakepc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

sealed interface Screen {
    data object Home : Screen

    data object Settings : Screen

    data object Console : Screen

    /** First-time setup, and adding a machine later: the same checked steps. */
    data object Setup : Screen

    data class EditConnection(
        val id: String?,
        val fromEmpty: Boolean = false,
    ) : Screen

    data class EditMachine(
        val id: String?,
    ) : Screen
}

/** Survives rotation and process death; every screen is three plain values. */
private val screenSaver =
    listSaver<Screen, Any?>(
        save = { screen ->
            when (screen) {
                Screen.Home -> listOf("home", null, false)
                Screen.Settings -> listOf("settings", null, false)
                Screen.Console -> listOf("console", null, false)
                Screen.Setup -> listOf("setup", null, false)
                is Screen.EditConnection -> listOf("connection", screen.id, screen.fromEmpty)
                is Screen.EditMachine -> listOf("machine", screen.id, false)
            }
        },
        restore = { saved ->
            val id = saved[1] as String?
            when (saved[0]) {
                "settings" -> Screen.Settings
                "console" -> Screen.Console
                "setup" -> Screen.Setup
                "connection" -> Screen.EditConnection(id, saved[2] as Boolean)
                "machine" -> Screen.EditMachine(id)
                else -> Screen.Home
            }
        },
    )

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(surface = Palette.bg, background = Palette.bg)) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Palette.bg)
                            .safeDrawingPadding(),
                ) {
                    App(store)
                }
            }
        }
    }
}

@Composable
private fun App(store: Store) {
    val state by store.state.collectAsState(initial = null)
    var screen by rememberSaveable(stateSaver = screenSaver) {
        mutableStateOf<Screen>(Screen.Home)
    }
    val current = state ?: return

    // A fresh install opens on setup with nothing behind it, so back leaves the app.
    val setupIsHome = screen == Screen.Setup && current.connections.isEmpty()
    BackHandler(enabled = screen != Screen.Home && !setupIsHome) {
        screen =
            when (val s = screen) {
                is Screen.EditConnection -> if (s.fromEmpty) Screen.Setup else Screen.Settings
                else -> Screen.Home
            }
    }

    when (val s = screen) {
        Screen.Home -> {
            if (current.connections.isEmpty()) {
                // Setup saves the relay partway through; as its own screen it
                // is not torn down the moment that makes this branch false.
                LaunchedEffect(Unit) { screen = Screen.Setup }
            } else {
                val homeVm: HomeViewModel =
                    viewModel(
                        factory = viewModelFactory { initializer { HomeViewModel(store.state) } },
                    )
                HomeScreen(
                    vm = homeVm,
                    onAddMachine = { screen = Screen.Setup },
                    onEditMachine = { screen = Screen.EditMachine(it) },
                    onSettings = { screen = Screen.Settings },
                    onConsole = { screen = Screen.Console },
                )
            }
        }

        Screen.Console -> {
            ConsoleScreen(onBack = { screen = Screen.Home })
        }

        Screen.Setup -> {
            SetupScreen(
                store = store,
                state = current,
                onBack = if (current.connections.isEmpty()) null else ({ screen = Screen.Home }),
                onAdvanced = { screen = Screen.EditConnection(null, fromEmpty = true) },
                onDone = { screen = Screen.Home },
            )
        }

        Screen.Settings -> {
            SettingsScreen(
                store = store,
                state = current,
                onEditConnection = { screen = Screen.EditConnection(it) },
                onBack = { screen = Screen.Home },
            )
        }

        is Screen.EditConnection -> {
            ConnectionEditor(
                store = store,
                connectionId = s.id,
                // Saved from setup's advanced link: carry on into picking the PC.
                onSaved = { screen = if (s.fromEmpty) Screen.Setup else Screen.Settings },
                onClosed = { screen = if (s.fromEmpty) Screen.Setup else Screen.Settings },
            )
        }

        is Screen.EditMachine -> {
            MachineEditor(
                store = store,
                machineId = s.id,
                onDone = { screen = Screen.Home },
            )
        }
    }
}
