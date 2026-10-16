package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.NotebookSettings
import com.example.transcription.GeminiTranscriptionFormat

/**
 * Settings dialog for configuring automatic transcription, fallback rules,
 * default transcription format, and contextual automation.
 */
@Composable
fun SettingsDialog(
    settings: NotebookSettings,
    isGeminiConfigured: Boolean,
    onAutoTitleChange: (Boolean) -> Unit,
    onAutoTranscribeChange: (Boolean) -> Unit,
    onAutoFallbackChange: (Boolean) -> Unit,
    onCloudAiChange: (Boolean) -> Unit,
    onFormatChange: (GeminiTranscriptionFormat) -> Unit,
    onReminderDetectionChange: (Boolean) -> Unit,
    onReminderSchedulingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("settings_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Transcription & Automation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_settings_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Settings")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 1: Transcription Engine & Automation
                Text(
                    text = "TRANSCRIPTION AUTOMATION",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Auto transcribe switch
                SettingSwitchRow(
                    icon = Icons.Default.Subtitles,
                    title = "Automatic Transcription",
                    subtitle = "Start transcription automatically after a recording is saved.",
                    checked = settings.autoTranscription,
                    onCheckedChange = onAutoTranscribeChange,
                    testTag = "setting_switch_auto_transcribe"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Cloud AI (Gemini) switch
                SettingSwitchRow(
                    icon = Icons.Default.CloudQueue,
                    title = "Cloud AI (Gemini) Enabled",
                    subtitle = if (isGeminiConfigured) {
                        "Allow cloud AI for advanced multilingual transcription. Disabling strictly keeps audio on-device."
                    } else {
                        "Gemini API key is not configured in Secrets. Local Whisper will be used."
                    },
                    checked = settings.cloudAiEnabled,
                    onCheckedChange = onCloudAiChange,
                    enabled = isGeminiConfigured,
                    testTag = "setting_switch_cloud_ai"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Auto Gemini fallback switch
                SettingSwitchRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "Automatic Gemini Fallback",
                    subtitle = "Fallback to Gemini AI if local Whisper transcription produces empty output or fails.",
                    checked = settings.autoGeminiFallback,
                    onCheckedChange = onAutoFallbackChange,
                    enabled = settings.cloudAiEnabled && isGeminiConfigured,
                    testTag = "setting_switch_auto_fallback"
                )

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 2: Default Transcription Format (Gemini AI)
                Text(
                    text = "DEFAULT TRANSCRIPTION FORMAT (AI)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Applied to automatic Gemini transcriptions. Output format dictates text representation, distinct from the recognition engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                GeminiTranscriptionFormat.values().forEach { format ->
                    val isSelected = settings.defaultTranscriptionFormat == format
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onFormatChange(format) }
                            .testTag("format_option_${format.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onFormatChange(format) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = format.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = format.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 3: Smart Automation
                Text(
                    text = "SMART AUTOMATION",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Contextual title generation
                SettingSwitchRow(
                    icon = Icons.Default.Title,
                    title = "Automatic Title Generation",
                    subtitle = "Derives a concise, contextual title from the transcription rather than a generic timestamp.",
                    checked = settings.autoTitleGeneration,
                    onCheckedChange = onAutoTitleChange,
                    testTag = "setting_switch_auto_title"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Auto reminder detection
                SettingSwitchRow(
                    icon = Icons.Default.FormatQuote,
                    title = "Automatic Reminder Detection",
                    subtitle = "Analyzes transcripts for reminder keywords and temporal phrases in English and Bangla.",
                    checked = settings.autoReminderDetection,
                    onCheckedChange = onReminderDetectionChange,
                    testTag = "setting_switch_reminder_detection"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Auto reminder scheduling
                SettingSwitchRow(
                    icon = Icons.Default.Alarm,
                    title = "Automatic Reminder Scheduling",
                    subtitle = "Schedules a device alarm when a valid explicit future date and time is detected.",
                    checked = settings.autoReminderScheduling,
                    onCheckedChange = onReminderSchedulingChange,
                    enabled = settings.autoReminderDetection,
                    testTag = "setting_switch_reminder_scheduling"
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        )
    }
}
