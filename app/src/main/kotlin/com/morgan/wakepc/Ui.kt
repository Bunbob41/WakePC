package com.morgan.wakepc

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val bg = Color(0xFF0B0D0F)
    val card = Color(0xFF101315)
    val border = Color(0xFF1E242A)
    val dashed = Color(0xFF242B31)
    val hairline = Color(0xFF14181C)
    val text = Color(0xFFE6E3DC)
    val sub = Color(0xFFAAB2B9)
    val dim = Color(0xFF5F6871)
    val faint = Color(0xFF3A434C)
    val red = Color(0xFFFF5D49)
    val green = Color(0xFF45D06D)
    val amber = Color(0xFFE2A63D)
    val heroBorder = Color(0xFF43241F)
    val heroBg = Color(0xFF170F0D)
    val selBorder = Color(0xFF2B4A36)
    val selBg = Color(0xFF101712)
    val activeBorder = Color(0xFF3A2A17)
    val activeBg = Color(0xFF14100B)
}

val Mono = FontFamily.Monospace

@Composable
fun pulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    return alpha
}

@Composable
fun Led(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulse: Boolean = false) {
    val alpha = if (pulse) pulseAlpha() else 1f
    Box(modifier = modifier.size(size * 3).alpha(alpha), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.4f), Color.Transparent)),
                ),
        )
        Box(modifier = Modifier.size(size).background(color, CircleShape))
    }
}

@Composable
fun StatusText(text: String, color: Color, pulse: Boolean = false, glow: Boolean = false) {
    val alpha = if (pulse) pulseAlpha() else 1f
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = Mono,
        letterSpacing = 1.5.sp,
        style = if (glow) {
            TextStyle(shadow = Shadow(color = color.copy(alpha = 0.75f), blurRadius = 14f))
        } else {
            TextStyle.Default
        },
        modifier = Modifier.alpha(alpha),
    )
}

@Composable
fun ConsoleText(
    text: String,
    size: Int,
    color: Color = Palette.text,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontFamily = Mono,
        fontWeight = weight,
        letterSpacing = letterSpacing.sp,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun SectionLabel(text: String) {
    ConsoleText(text, size = 10, color = Palette.dim, letterSpacing = 2.0)
}

@Composable
fun ConsoleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Palette.text,
            fontSize = 14.sp,
            fontFamily = Mono,
            letterSpacing = if (secret) 3.sp else 0.sp,
        ),
        cursorBrush = SolidColor(Palette.text),
        visualTransformation = if (secret) PasswordVisualTransformation('•') else VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.card, RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) {
                    ConsoleText(placeholder, size = 14, color = Palette.faint)
                }
                inner()
            }
        },
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.text, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 17.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = Palette.bg,
            fontSize = 13.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
    }
}

fun Modifier.dashedBorder(color: Color, corner: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        ),
        cornerRadius = CornerRadius(corner.toPx()),
    )
}

@Composable
fun TintedIcon(resId: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
