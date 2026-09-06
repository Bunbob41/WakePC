package com.morgan.wakepc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The confirmation gate. A command does not run until the right code is
 * entered, which is what stops a pocket tap shutting a machine down.
 *
 * Its own keypad rather than the system keyboard: these are always digits,
 * the dialog is small, and it keeps the console look.
 */
@Composable
fun PinPrompt(
    title: String,
    subtitle: String,
    expected: String,
    onDismiss: () -> Unit,
    onAccepted: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun press(digit: String) {
        if (entered.length >= expected.length) return
        wrong = false
        entered += digit
        if (entered.length == expected.length) {
            if (entered == expected) {
                onAccepted()
            } else {
                wrong = true
                entered = ""
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .background(Palette.card, RoundedCornerShape(12.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConsoleText(title, size = 13, weight = FontWeight.Bold, letterSpacing = 2.0)
            ConsoleText(subtitle, size = 11, color = Palette.dim)

            // One dot per expected digit, filling as you type.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(expected.length) { index ->
                    val filled = index < entered.length
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .background(
                                    if (wrong) {
                                        Palette.red
                                    } else if (filled) {
                                        Palette.green
                                    } else {
                                        Palette.dashed
                                    },
                                    CircleShape,
                                ),
                    )
                }
            }
            if (wrong) {
                ConsoleText("wrong code", size = 11, color = Palette.red)
            }

            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
                .forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { Key(it) { press(it) } }
                    }
                }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("cancel", wide = true, onClick = onDismiss)
                Key("0") { press("0") }
                Key("del", wide = true) {
                    wrong = false
                    entered = entered.dropLast(1)
                }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    wide: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(width = if (wide) 74.dp else 60.dp, height = 52.dp)
                .background(Palette.bg, RoundedCornerShape(8.dp))
                .border(1.dp, Palette.dashed, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ConsoleText(label, size = if (wide) 11 else 18, color = if (wide) Palette.dim else Palette.text)
    }
}

/** Lets a caller collect a code without knowing what it will be checked against. */
@Composable
fun PinEntry(
    title: String,
    subtitle: String,
    length: Int,
    onDismiss: () -> Unit,
    onEntered: (String) -> Unit,
) {
    var entered by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .background(Palette.card, RoundedCornerShape(12.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConsoleText(title, size = 13, weight = FontWeight.Bold, letterSpacing = 2.0)
            ConsoleText(subtitle, size = 11, color = Palette.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(length) { index ->
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .background(
                                    if (index < entered.length) Palette.green else Palette.dashed,
                                    CircleShape,
                                ),
                    )
                }
            }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
                .forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { digit ->
                            Key(digit) {
                                if (entered.length < length) {
                                    entered += digit
                                    if (entered.length == length) onEntered(entered)
                                }
                            }
                        }
                    }
                }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("cancel", wide = true, onClick = onDismiss)
                Key("0") {
                    if (entered.length < length) {
                        entered += "0"
                        if (entered.length == length) onEntered(entered)
                    }
                }
                Key("del", wide = true) { entered = entered.dropLast(1) }
            }
        }
    }
}
