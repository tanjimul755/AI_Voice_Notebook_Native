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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.VoiceNote
import com.example.transcription.WhisperModelStatus

data class LanguageOption(val code: String, val label: String)

val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("auto", "Auto Detect"),
    LanguageOption("en", "English"),
    LanguageOption("bn", "Bangla (বাংলা)")
)

/**
 * Dialog enabling language selection, model verification, and initiation of offline Whisper STT.
 * Explains how the model is obtained/stored and gives user explicit control before downloading.
 */
@Composable
fun TranscribeDialog(
    note: VoiceNote,
    modelStatus: WhisperModelStatus,
    isDownloadingModel: Boolean,
    downloadProgress: Float?,
    isTranscribing: Boolean,
    onDownloadModel: () -> Unit,
    onStartTranscribe: (language: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLanguage by remember { mutableStateOf("auto") }

    AlertDialog(
        onDismissRequest = {
            if (!isDownloadingModel && !isTranscribing) {
                onDismiss()
            }
        },
        modifier = modifier.testTag("transcribe_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Default.Subtitles,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Offline Transcription",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Note: \"${note.title}\"",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Language Selection Row
                Text(
                    text = "Spoken Language:",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    SUPPORTED_LANGUAGES.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isDownloadingModel && !isTranscribing) {
                                    selectedLanguage = option.code
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLanguage == option.code,
                                onClick = { selectedLanguage = option.code },
                                enabled = !isDownloadingModel && !isTranscribing,
                                modifier = Modifier.testTag("radio_lang_${option.code}")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Model Status & Privacy Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (modelStatus.isAvailable) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (modelStatus.isAvailable) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.CloudDownload
                                },
                                contentDescription = null,
                                tint = if (modelStatus.isAvailable) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (modelStatus.isAvailable) {
                                    "Whisper Model Ready (Offline)"
                                } else {
                                    "Whisper Model Setup"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (modelStatus.isAvailable) {
                            Text(
                                text = "Multilingual Whisper model (${modelStatus.modelName}) is installed locally. Audio is processed 100% on-device with zero cloud telemetry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Transcription runs completely offline. To transcribe, download the lightweight multilingual Whisper model (~75 MB, ggml-tiny.bin) once to your device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            if (isDownloadingModel) {
                                val progressVal = downloadProgress ?: 0f
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    LinearProgressIndicator(
                                        progress = { progressVal },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Downloading model: ${(progressVal * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                Button(
                                    onClick = onDownloadModel,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("download_model_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download Whisper Model (~75 MB)")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (modelStatus.isAvailable) {
                Button(
                    onClick = { onStartTranscribe(selectedLanguage) },
                    enabled = !isTranscribing,
                    modifier = Modifier.testTag("confirm_transcribe_button")
                ) {
                    if (isTranscribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Transcribing...")
                    } else {
                        Text("Start Transcription")
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isDownloadingModel && !isTranscribing,
                modifier = Modifier.testTag("dismiss_transcribe_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
