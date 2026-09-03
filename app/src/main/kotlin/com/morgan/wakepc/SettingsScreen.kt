package com.morgan.wakepc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    store: Store,
    state: AppState,
    onEditConnection: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<String?>(null) } // "hero" | "tile"

    fun describe(ref: ButtonRef?): String {
        val resolved = state.resolve(ref) ?: return "not set"
        return "${resolved.command.name} · ${resolved.machine.name}"
    }

    EditorScaffold("SETTINGS", onBack = onBack) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("HERO BUTTON")
                SettingsRow(describe(state.hero), null) { picking = "hero" }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroStyle.entries.forEach { style ->
                        val isSel = state.heroStyle == style
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSel) Palette.red.copy(alpha = 0.09f) else Palette.card,
                                    RoundedCornerShape(6.dp),
                                )
                                .border(
                                    1.dp,
                                    if (isSel) Palette.red else Palette.border,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable {
                                    scope.launch { store.update { it.copy(heroStyle = style) } }
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ConsoleText(
                                style.name.lowercase(),
                                size = 11,
                                color = if (isSel) Palette.text else Palette.dim,
                                letterSpacing = 1.0,
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("QUICK SETTINGS TILE")
                SettingsRow(describe(state.tile), "fires from the notification shade") { picking = "tile" }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("CONNECTIONS")
                state.connections.forEach { conn ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.card, RoundedCornerShape(6.dp))
                            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                            .clickable { onEditConnection(conn.id) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(Color(conn.color), CircleShape))
                        ConsoleText(conn.name, size = 13, modifier = Modifier.weight(1f))
                        TintedIcon(R.drawable.ic_chevron, Palette.faint, size = 14.dp)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedBorder(Palette.dashed, 6.dp)
                        .clickable { onEditConnection(null) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ConsoleText("+ add connection", size = 12, color = Palette.dim)
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            ConsoleText(
                "wakepc ${BuildConfigVersion.NAME} · talks only to your tailnet",
                size = 10,
                color = Palette.faint,
            )
        }
    }

    if (picking != null) {
        val options = state.machines.flatMap { machine ->
            machine.commands.map { cmd -> ButtonRef(machine.id, cmd.name) }
        }
        Dialog(onDismissRequest = { picking = null }) {
            Column(
                modifier = Modifier
                    .background(Palette.card, RoundedCornerShape(10.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel(if (picking == "hero") "HERO BUTTON" else "TILE FIRES")
                options.forEach { ref ->
                    val label = describe(ref)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                            .clickable {
                                val target = picking
                                picking = null
                                scope.launch {
                                    store.update {
                                        if (target == "hero") it.copy(hero = ref) else it.copy(tile = ref)
                                    }
                                }
                            }
                            .padding(13.dp),
                    ) {
                        ConsoleText(label, size = 13)
                    }
                }
                if (options.isEmpty()) {
                    ConsoleText("no buttons yet — add a machine first", size = 11, color = Palette.dim)
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(value: String, hint: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.card, RoundedCornerShape(6.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ConsoleText(value, size = 13)
            if (hint != null) {
                ConsoleText(hint, size = 10, color = Palette.dim)
            }
        }
        TintedIcon(R.drawable.ic_chevron, Palette.faint, size = 14.dp)
    }
}

object BuildConfigVersion {
    const val NAME = "0.3.0"
}
