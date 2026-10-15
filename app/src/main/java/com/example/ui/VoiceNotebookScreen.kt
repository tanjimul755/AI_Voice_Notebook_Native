package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.DeleteConfirmationDialog
import com.example.ui.components.EditTitleDialog
import com.example.ui.components.NoteItemCard
import com.example.ui.components.PermissionRationaleCard
import com.example.ui.components.RecordingBottomBar
import com.example.ui.components.SaveNoteDialog

/**
 * Main application screen for AI Voice Notebook.
 * Coordinates note listing, audio playback, microphone recording bar, and search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNotebookScreen(
    viewModel: VoiceNoteViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearchExpanded by remember { mutableStateOf(false) }
    var hasPermissionDenied by remember { mutableStateOf(false) }

    // Launcher for RECORD_AUDIO runtime permission
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            hasPermissionDenied = false
            viewModel.startRecording()
        } else {
            hasPermissionDenied = true
        }
    }

    fun handleRecordClick() {
        val permissionCheck = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            hasPermissionDenied = false
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // React to messages via Snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { info ->
            snackbarHostState.showSnackbar(info)
            viewModel.clearInfoMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("voice_notebook_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "AI Voice Notebook",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${uiState.notes.size} ${if (uiState.notes.size == 1) "recording" else "recordings"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchExpanded = !isSearchExpanded
                            if (!isSearchExpanded) {
                                viewModel.setSearchQuery("")
                            }
                        },
                        modifier = Modifier.testTag("search_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchExpanded) "Close search" else "Search notes"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            RecordingBottomBar(
                recordingStatus = uiState.recordingStatus,
                onStartRecording = { handleRecordClick() },
                onPauseRecording = { viewModel.pauseRecording() },
                onResumeRecording = { viewModel.resumeRecording() },
                onStopRecording = { viewModel.stopRecording() },
                onCancelRecording = { viewModel.cancelRecording() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Collapsible Search Input
            AnimatedVisibility(visible = isSearchExpanded) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search by title or tags...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("search_text_field"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            }

            // Permission Banner (if previously denied)
            if (hasPermissionDenied) {
                PermissionRationaleCard(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Main Content Area
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("loading_indicator"))
                }
            } else if (uiState.notes.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No Matching Notes" else "Your Notebook is Empty",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) {
                                "No voice notes match \"${uiState.searchQuery}\". Try another search term."
                            } else {
                                "Tap 'Record Voice Note' below to capture your first thought, lecture, or meeting. Notes are stored permanently on your device."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            } else {
                // Notes List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("notes_lazy_column"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = uiState.notes,
                        key = { it.id }
                    ) { note ->
                        val isCurrent = uiState.playbackState.currentNoteId == note.id
                        NoteItemCard(
                            note = note,
                            isCurrentPlaying = isCurrent,
                            isPlaying = isCurrent && uiState.playbackState.isPlaying,
                            currentPositionMs = if (isCurrent) uiState.playbackState.currentPositionMs else 0L,
                            playbackDurationMs = if (isCurrent) uiState.playbackState.durationMs else note.durationMs,
                            onTogglePlay = { viewModel.togglePlayNote(note) },
                            onSeek = { seekPos -> viewModel.seekPlayback(seekPos) },
                            onToggleStar = { viewModel.toggleStarred(note) },
                            onEditTitle = { viewModel.promptEditTitle(note) },
                            onDelete = { viewModel.promptDeleteNote(note) }
                        )
                    }
                }
            }
        }
    }

    // Save Recording Dialog
    if (uiState.isSaveDialogVisible) {
        SaveNoteDialog(
            initialTitle = uiState.proposedTitle,
            durationMs = uiState.pendingDurationMs,
            file = uiState.pendingSavedFile,
            onSave = { title, tags -> viewModel.confirmSaveRecording(title, tags) },
            onDiscard = { viewModel.discardPendingRecording() }
        )
    }

    // Rename Note Dialog
    uiState.editingNote?.let { note ->
        EditTitleDialog(
            initialTitle = note.title,
            onConfirm = { newTitle -> viewModel.confirmEditTitle(newTitle) },
            onDismiss = { viewModel.dismissEditTitleDialog() }
        )
    }

    // Delete Confirmation Dialog
    uiState.noteToDelete?.let { note ->
        DeleteConfirmationDialog(
            noteTitle = note.title,
            onConfirm = { viewModel.confirmDeleteNote() },
            onDismiss = { viewModel.dismissDeleteDialog() }
        )
    }
}
