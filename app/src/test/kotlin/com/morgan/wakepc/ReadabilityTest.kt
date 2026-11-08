package com.morgan.wakepc

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * 0.14.0's two darker greys were too dark to read: the home screen's request
 * line and "via <relay>" sat at 1.9 to 1 against the background, at 10 sp.
 * These hold every text colour to the WCAG AA minimum for body text (4.5 to 1)
 * on every surface text is drawn on, and every text size to 11 sp or more.
 */
class ReadabilityTest {
    private val surfaces =
        mapOf(
            "bg" to Palette.bg,
            "card" to Palette.card,
            "heroBg" to Palette.heroBg,
            "selBg" to Palette.selBg,
            "activeBg" to Palette.activeBg,
        )

    private val textColours =
        mapOf(
            "text" to Palette.text,
            "sub" to Palette.sub,
            "dim" to Palette.dim,
            "faint" to Palette.faint,
            "red" to Palette.red,
            "green" to Palette.green,
            "amber" to Palette.amber,
        )

    @Test
    fun `every text colour is readable on every surface`() {
        val failures =
            textColours.flatMap { (name, colour) ->
                surfaces.mapNotNull { (surface, background) ->
                    val ratio = contrast(colour, background)
                    if (ratio < MIN_CONTRAST) "$name on $surface is ${"%.2f".format(ratio)} to 1" else null
                }
            }
        assertTrue("below $MIN_CONTRAST to 1: $failures", failures.isEmpty())
    }

    @Test
    fun `the greys still step down from text to faint`() {
        val order = listOf(Palette.text, Palette.sub, Palette.dim, Palette.faint).map { contrast(it, Palette.bg) }
        assertTrue("contrast should fall from text to faint: $order", order.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun `no text is smaller than 11 sp`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        val sizes =
            sources.flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    textSizes(line).map { Triple(file.name, index + 1, it) }
                }
            }
        // An empty scan would pass for the wrong reason, so check it found the app's text.
        assertTrue("found only ${sizes.size} text sizes, is the scan reading the sources?", sizes.size >= MIN_SIZES_FOUND)
        val small = sizes.filter { it.third < MIN_SP }.map { "${it.first}:${it.second} is ${it.third} sp" }
        assertTrue("text under $MIN_SP sp: $small", small.isEmpty())
    }

    /** Sizes in `fontSize = N.sp`, `console(N, ...)` and `size = N` (but not `size = N.dp`). */
    private fun textSizes(line: String): List<Int> =
        Regex("""fontSize = (\d+)\.sp""").findAll(line).map { it.groupValues[1].toInt() }.toList() +
            Regex("""console\((\d+),""").findAll(line).map { it.groupValues[1].toInt() }.toList() +
            Regex("""\bsize = ([^,\n)]*)""")
                .findAll(line)
                .map { it.groupValues[1] }
                .filterNot { it.contains(".dp") }
                .flatMap { expr -> Regex("""\b(\d+)\b""").findAll(expr).map { it.groupValues[1].toInt() } }
                .toList()

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private companion object {
        const val MIN_CONTRAST = 4.5
        const val MIN_SP = 11
        const val MIN_SIZES_FOUND = 30
    }
}
