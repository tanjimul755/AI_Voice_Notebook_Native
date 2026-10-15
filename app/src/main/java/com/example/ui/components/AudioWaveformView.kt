package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Animated audio waveform visualizer for recording sessions.
 * Displays vertical rounded bars that dynamically adjust height based on live microphone amplitude.
 */
@Composable
fun AudioWaveformView(
    amplitude: Int,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 24
) {
    // Normalized amplitude factor [0.0f, 1.0f]
    val normalizedAmp = (amplitude.toFloat() / 25000f).coerceIn(0.05f, 1.0f)

    // Animated smoothing
    val smoothAmp = remember { Animatable(0.1f) }

    LaunchedEffect(amplitude, isRecording) {
        if (isRecording) {
            smoothAmp.animateTo(
                targetValue = normalizedAmp,
                animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing)
            )
        } else {
            smoothAmp.animateTo(
                targetValue = 0.08f,
                animationSpec = tween(durationMillis = 200)
            )
        }
    }

    // Seeded random variation to give realistic waveform look
    val barRatios = remember {
        List(barCount) {
            0.3f + Random(it * 17).nextFloat() * 0.7f
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val width = size.width
        val height = size.height
        val totalSpacing = width * 0.02f
        val barWidth = (width - (barCount - 1) * totalSpacing) / barCount
        val minBarHeight = height * 0.12f

        for (i in 0 until barCount) {
            val factor = barRatios[i]
            val activeHeight = if (isRecording) {
                (height * smoothAmp.value * factor).coerceIn(minBarHeight, height * 0.95f)
            } else {
                minBarHeight
            }

            val left = i * (barWidth + totalSpacing)
            val top = (height - activeHeight) / 2f

            drawRoundRect(
                color = barColor.copy(alpha = if (isRecording) 0.9f else 0.4f),
                topLeft = Offset(left, top),
                size = Size(barWidth, activeHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}
