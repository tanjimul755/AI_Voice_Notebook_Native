package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.AudioFileManager
import java.io.File

/**
 * Dialog presented after finishing an audio recording to give it a custom title and optional tags.
 */
@Composable
fun SaveNoteDialog(
    initialTitle: String,
    durationMs: Long,
    file: File?,
    onSave: (title: String, tags: String) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var tags by remember { mutableStateOf("") }

    val formattedDuration = AudioFileManager.formatDuration(durationMs)
    val fileSizeFormatted = file?.let { AudioFileManager.formatFileSize(it.length()) } ?: "0 KB"

    AlertDialog(
        onDismissRequest = onDiscard,
        modifier = modifier.testTag("save_note_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Save Voice Note",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Audio stats chip row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Duration: $formattedDuration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Size: $fileSizeFormatted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Note Title") },
                    placeholder = { Text("e.g., Software Engineering Lecture") },
                    leadingIcon = {
                        Icon(Icons.Default.Title, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags / Category (Optional)") },
                    placeholder = { Text("e.g., Study, Idea, Meeting") },
                    leadingIcon = {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_tags_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), tags.trim()) },
                modifier = Modifier.testTag("save_confirm_button")
            ) {
                Text("Save to Notebook")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDiscard,
                modifier = Modifier.testTag("discard_recording_button")
            ) {
                Text("Discard")
            }
        }
    )
}
