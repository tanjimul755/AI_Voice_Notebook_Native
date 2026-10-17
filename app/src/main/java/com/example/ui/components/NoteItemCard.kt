package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.AudioFileManager
import com.example.model.VoiceNote
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Notebook item card representing a saved voice note.
 * Displays metadata, playback status, scrubbing slider, and management actions.
 */
@Composable
fun NoteItemCard(
    note: VoiceNote,
    isCurrentPlaying: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    playbackDurationMs: Long,
    isTranscribing: Boolean = false,
    isAiTranscribing: Boolean = false,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleStar: () -> Unit,
    onEditTitle: () -> Unit,
    onDelete: () -> Unit,
    onTranscribe: () -> Unit = {},
    onAiTranscribe: () -> Unit = {},
    onEditReminder: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val effectiveDuration = if (isCurrentPlaying && playbackDurationMs > 0) {
        playbackDurationMs
    } else {
        note.durationMs
    }

    val progress = if (isCurrentPlaying && effectiveDuration > 0) {
        (currentPositionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("note_card_${note.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentPlaying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentPlaying) 4.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Title, Compact Reminder Indicator, and Actions Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Recording status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCurrentPlaying && isPlaying) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            }
                        )
                )

                Spacer(modifier = Modifier.width(8.dp))

                val displayTitle = if (note.title.isBlank() || note.title.startsWith("Voice Note")) {
                    if (!note.transcription.isNullOrBlank() && note.transcription != "[No speech detected in audio]") {
                        note.transcription.lines().firstOrNull { it.isNotBlank() }?.take(45) ?: note.title
                    } else {
                        note.title.ifBlank { "Voice Note" }
                    }
                } else {
                    note.title
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (note.isStarred) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorited",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Compact Clickable Reminder Indicator (e.g. 🔔 10:30 AM or 🔔 Set time)
                val hasReminder = note.reminderTimestamp != null || !note.reminderTask.isNullOrBlank() || note.reminderStatus != "NONE"
                if (hasReminder) {
                    val reminderTimeText = when {
                        note.reminderTimestamp != null -> {
                            val cal = Calendar.getInstance().apply { timeInMillis = note.reminderTimestamp }
                            val nowCal = Calendar.getInstance()
                            if (cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                                cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
                            ) {
                                SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
                            } else {
                                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(cal.time)
                            }
                        }
                        else -> "Set time"
                    }

                    val isCompleted = note.reminderStatus == "COMPLETED"
                    val isOff = !note.isReminderEnabled && !isCompleted

                    val bellIcon = when {
                        isCompleted -> Icons.Default.Notifications
                        isOff -> Icons.Default.NotificationsNone
                        note.reminderTimestamp == null -> Icons.Default.Notifications
                        else -> Icons.Default.NotificationsActive
                    }

                    val pillBg = when {
                        isCompleted -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        isOff -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        note.reminderTimestamp == null -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }

                    val pillFg = when {
                        isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                        isOff -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        note.reminderTimestamp == null -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    }

                    val displayText = when {
                        isOff -> if (note.reminderTimestamp != null) "$reminderTimeText (Off)" else "Off"
                        else -> reminderTimeText
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = pillBg,
                        modifier = Modifier
                            .testTag("reminder_bell_button_${note.id}")
                            .clickable { onEditReminder() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = bellIcon,
                                contentDescription = "Reminder: $displayText. Tap to edit.",
                                tint = pillFg,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = pillFg
                            )
                            if (isCompleted) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Completed",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier
                                        .size(13.dp)
                                        .testTag("reminder_completed_indicator_${note.id}")
                                )
                            }
                        }
                    }
                }

                // Three-dot actions menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("more_menu_button_${note.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Note actions"
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        // Favorite / Unfavorite action
                        DropdownMenuItem(
                            text = { Text(if (note.isStarred) "Remove from Favorites" else "Add to Favorites") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (note.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    tint = if (note.isStarred) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onToggleStar()
                            },
                            modifier = Modifier.testTag("favorite_menu_item_${note.id}")
                        )

                        DropdownMenuItem(
                            text = { Text(if (hasReminder) "Edit Reminder" else "Set Reminder") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onEditReminder()
                            },
                            modifier = Modifier.testTag("reminder_menu_item_${note.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Transcribe with AI (Gemini)") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onAiTranscribe()
                            },
                            modifier = Modifier.testTag("ai_transcribe_menu_item_${note.id}")
                        )
                        DropdownMenuItem(
                            text = { Text(if (note.transcription.isNullOrBlank()) "Transcribe (Offline Whisper)" else "Re-transcribe (Offline)") },
                            leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                showMenu = false
                                onTranscribe()
                            },
                            modifier = Modifier.testTag("transcribe_menu_item_${note.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Rename Note") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                showMenu = false
                                onEditTitle()
                            },
                            modifier = Modifier.testTag("rename_menu_item_${note.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Note", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            modifier = Modifier.testTag("delete_menu_item_${note.id}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Metadata Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = AudioFileManager.formatTimestamp(note.createdAtTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = AudioFileManager.formatFileSize(note.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (note.tags.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = note.tags,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Playback controls row: Play/Pause button + Seek Slider + Time label
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("play_pause_button_${note.id}"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isCurrentPlaying && isPlaying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                ) {
                    Icon(
                        imageVector = if (isCurrentPlaying && isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (isCurrentPlaying && isPlaying) "Pause audio" else "Play audio"
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = progress,
                        onValueChange = { newProgress ->
                            val seekMs = (newProgress * effectiveDuration).toLong()
                            onSeek(seekMs)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("audio_slider_${note.id}"),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val currentText = if (isCurrentPlaying) {
                            AudioFileManager.formatDuration(currentPositionMs)
                        } else {
                            "00:00"
                        }
                        Text(
                            text = currentText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = AudioFileManager.formatDuration(effectiveDuration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Transcription section / Transcribe Action
            Spacer(modifier = Modifier.height(12.dp))

            if (isAiTranscribing) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transcribing_ai_indicator_${note.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Transcribing with Gemini AI...",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else if (isTranscribing) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transcribing_indicator_${note.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Transcribing with Whisper (Offline)...",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            } else if (!note.transcription.isNullOrBlank()) {
                val clipboardManager = LocalClipboardManager.current
                var copiedState by remember { mutableStateOf(false) }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TRANSCRIPT",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(note.transcription))
                                    copiedState = true
                                },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("copy_transcript_button_${note.id}")
                            ) {
                                Icon(
                                    imageVector = if (copiedState) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                                    contentDescription = "Copy transcript",
                                    tint = if (copiedState) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = note.transcription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("note_transcript_${note.id}")
                        )
                    }
                }
            } else {
                // If not transcribed yet, offer quick action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onTranscribe,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("transcribe_button_${note.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Transcribe (Offline)",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    FilledTonalButton(
                        onClick = onAiTranscribe,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_transcribe_button_${note.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Transcribe with AI",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
