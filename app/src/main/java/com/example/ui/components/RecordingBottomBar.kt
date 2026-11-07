package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audio.AudioFileManager
import com.example.data.MicInputMode
import com.example.ui.RecordingStatus

/**
 * Bottom control panel for initiating, controlling, and finishing voice recordings.
 * Supports both Traditional input (tap to record / tap to save) and
 * WhatsApp-style Push to Talk (hold to record with start/stop sounds, slide to cancel, release to save).
 */
@Composable
fun RecordingBottomBar(
    recordingStatus: RecordingStatus,
    micInputMode: MicInputMode = MicInputMode.TRADITIONAL,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onPushToTalkStart: () -> Unit = onStartRecording,
    onPushToTalkStop: (Long) -> Unit = { onStopRecording() },
    onPushToTalkCancel: () -> Unit = onCancelRecording,
    modifier: Modifier = Modifier
) {
    val isRecordingActive = recordingStatus !is RecordingStatus.Idle

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (micInputMode == MicInputMode.PUSH_TO_TALK) {
                // ==========================================
                // PUSH TO TALK MODE (WhatsApp Style)
                // ==========================================
                PushToTalkControl(
                    recordingStatus = recordingStatus,
                    onPushToTalkStart = onPushToTalkStart,
                    onPushToTalkStop = onPushToTalkStop,
                    onPushToTalkCancel = onPushToTalkCancel
                )
            } else {
                // ==========================================
                // TRADITIONAL MODE (Tap to Start / Stop)
                // ==========================================
                TraditionalRecordingControl(
                    isRecordingActive = isRecordingActive,
                    recordingStatus = recordingStatus,
                    onStartRecording = onStartRecording,
                    onPauseRecording = onPauseRecording,
                    onResumeRecording = onResumeRecording,
                    onStopRecording = onStopRecording,
                    onCancelRecording = onCancelRecording
                )
            }
        }
    }
}

/**
 * Traditional recording controls: Tap to start, tap stop to finish, with pause/resume and discard.
 */
@Composable
private fun TraditionalRecordingControl(
    isRecordingActive: Boolean,
    recordingStatus: RecordingStatus,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit
) {
    // IDLE STATE
    AnimatedVisibility(
        visible = !isRecordingActive,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        ElevatedButton(
            onClick = onStartRecording,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("record_fab"),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Start Recording",
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Record Voice Note",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }

    // ACTIVE RECORDING / PAUSED STATE
    AnimatedVisibility(
        visible = isRecordingActive,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        val isPaused = recordingStatus is RecordingStatus.Paused
        val durationMs = when (recordingStatus) {
            is RecordingStatus.Recording -> recordingStatus.durationMs
            is RecordingStatus.Paused -> recordingStatus.durationMs
            is RecordingStatus.Idle -> 0L
        }
        val amplitude = when (recordingStatus) {
            is RecordingStatus.Recording -> recordingStatus.amplitude
            else -> 0
        }

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "recordingDotAlpha"
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Status Badge + Live Timer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .alpha(if (isPaused) 1f else pulseAlpha)
                            .background(if (isPaused) MaterialTheme.colorScheme.tertiary else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPaused) "RECORDING PAUSED" else "RECORDING AUDIO",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = AudioFileManager.formatDuration(durationMs),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Audio Waveform Visualization
            AudioWaveformView(
                amplitude = amplitude,
                isRecording = !isPaused,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                barColor = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Controls Row: Discard | Pause/Resume | Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedIconButton(
                    onClick = onCancelRecording,
                    modifier = Modifier
                        .size(50.dp)
                        .testTag("cancel_recording_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Discard Recording",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                FilledTonalIconButton(
                    onClick = {
                        if (isPaused) onResumeRecording() else onPauseRecording()
                    },
                    modifier = Modifier
                        .size(54.dp)
                        .testTag(if (isPaused) "resume_recording_button" else "pause_recording_button")
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume Recording" else "Pause Recording",
                        modifier = Modifier.size(28.dp)
                    )
                }

                FilledIconButton(
                    onClick = onStopRecording,
                    modifier = Modifier
                        .size(54.dp)
                        .testTag("stop_recording_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Stop and Save Recording",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * WhatsApp-style Push to Talk control:
 * - Press down: plays start chirp, vibrates, and starts recording immediately.
 * - Live state: shows pulsing recording dot, waveform, and sliding cancellation guide.
 * - Drag left (>120px): cancels and highlights red trash can.
 * - Release: plays voice note sent pop chime and saves file immediately.
 */
@Composable
private fun PushToTalkControl(
    recordingStatus: RecordingStatus,
    onPushToTalkStart: () -> Unit,
    onPushToTalkStop: (Long) -> Unit,
    onPushToTalkCancel: () -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var isSlideCancelled by remember { mutableStateOf(false) }
    var holdStartTimestamp by remember { mutableLongStateOf(0L) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    val isRecordingActive = recordingStatus is RecordingStatus.Recording || isHolding
    val durationMs = when (recordingStatus) {
        is RecordingStatus.Recording -> recordingStatus.durationMs
        else -> 0L
    }
    val amplitude = when (recordingStatus) {
        is RecordingStatus.Recording -> recordingStatus.amplitude
        else -> 0
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pttPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pttRecordingDotAlpha"
    )

    val pttModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            isHolding = true
            isSlideCancelled = false
            dragOffsetX = 0f
            holdStartTimestamp = System.currentTimeMillis()
            onPushToTalkStart()

            while (true) {
                val event = awaitPointerEvent()
                val currentChange = event.changes.firstOrNull { it.id == down.id }
                if (currentChange == null || !currentChange.pressed) {
                    // Finger lifted
                    val totalHoldMs = System.currentTimeMillis() - holdStartTimestamp
                    if (isSlideCancelled) {
                        onPushToTalkCancel()
                    } else {
                        onPushToTalkStop(totalHoldMs)
                    }
                    isHolding = false
                    isSlideCancelled = false
                    dragOffsetX = 0f
                    break
                } else {
                    currentChange.consume()
                    val totalDragX = currentChange.position.x - down.position.x
                    dragOffsetX = totalDragX
                    if (totalDragX < -130f) {
                        isSlideCancelled = true
                    } else if (totalDragX > -40f) {
                        isSlideCancelled = false
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Active visual cues while user is holding
        AnimatedVisibility(
            visible = isRecordingActive,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Header: Live Time & Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .alpha(pulseAlpha)
                                .background(if (isSlideCancelled) MaterialTheme.colorScheme.error else Color.Red)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSlideCancelled) "RELEASE TO CANCEL" else "RECORDING (HOLDING)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isSlideCancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = AudioFileManager.formatDuration(durationMs),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (isSlideCancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Waveform visualization
                AudioWaveformView(
                    amplitude = amplitude,
                    isRecording = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    barColor = if (isSlideCancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Slide to Cancel Guide
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSlideCancelled) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Release to discard",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Release to cancel and discard",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "‹‹ Slide left to cancel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // The Push-to-Talk Mic Touch Target
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(29.dp))
                .testTag("record_fab")
                .testTag("push_to_talk_button")
                .then(pttModifier),
            shape = RoundedCornerShape(29.dp),
            color = when {
                isSlideCancelled -> MaterialTheme.colorScheme.errorContainer
                isRecordingActive -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.primary
            },
            shadowElevation = if (isRecordingActive) 6.dp else 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        isSlideCancelled -> Icons.Default.Delete
                        isRecordingActive -> Icons.Default.Mic
                        else -> Icons.Default.Mic
                    },
                    contentDescription = "Push to Talk Microphone",
                    modifier = Modifier.size(26.dp),
                    tint = when {
                        isSlideCancelled -> MaterialTheme.colorScheme.onErrorContainer
                        isRecordingActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onPrimary
                    }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = when {
                            isSlideCancelled -> "Release to Discard"
                            isRecordingActive -> "Recording... Release to Save"
                            else -> "Hold to Record"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = when {
                            isSlideCancelled -> MaterialTheme.colorScheme.onErrorContainer
                            isRecordingActive -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onPrimary
                        }
                    )
                    if (!isRecordingActive) {
                        Text(
                            text = "Release to save • Slide left to cancel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}
