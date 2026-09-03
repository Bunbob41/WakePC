package com.morgan.wakepc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConsoleScreen(onBack: () -> Unit) {
    val lines by AppLog.lines.collectAsState()
    val scroll = rememberScrollState()

    LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }

    EditorScaffold("CONSOLE", onBack = onBack) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (lines.isEmpty()) {
                ConsoleText("nothing yet — every request lands here", size = 11, color = Palette.faint)
            }
            lines.forEach { line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    ConsoleText(line.timestamp(), size = 10, color = Palette.faint)
                    ConsoleText(
                        line.text,
                        size = 11,
                        color = when (line.ok) {
                            true -> Palette.sub
                            false -> Palette.red
                            null -> Palette.dim
                        },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}
