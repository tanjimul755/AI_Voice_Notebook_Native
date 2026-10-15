package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AndroidAudioPlayer
import com.example.audio.AndroidAudioRecorder
import com.example.audio.AudioFileManager
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.data.AppDatabase
import com.example.data.VoiceNoteRepository
import com.example.data.VoiceNoteRepositoryImpl
import com.example.model.VoiceNote
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RecordingStatus {
    object Idle : RecordingStatus
    data class Recording(val durationMs: Long, val amplitude: Int) : RecordingStatus
    data class Paused(val durationMs: Long) : RecordingStatus
}

data class PlaybackState(
    val currentNoteId: Long? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

data class NotebookUiState(
    val notes: List<VoiceNote> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val playbackState: PlaybackState = PlaybackState(),
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isSaveDialogVisible: Boolean = false,
    val pendingSavedFile: File? = null,
    val pendingDurationMs: Long = 0L,
    val proposedTitle: String = "",
    val editingNote: VoiceNote? = null,
    val noteToDelete: VoiceNote? = null
)

private data class DialogsAndMessages(
    val isSaveDialogVisible: Boolean = false,
    val pendingSavedFile: File? = null,
    val pendingDurationMs: Long = 0L,
    val proposedTitle: String = "",
    val editingNote: VoiceNote? = null,
    val noteToDelete: VoiceNote? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class VoiceNoteViewModel(
    application: Application,
    private val repository: VoiceNoteRepository,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val audioFileManager: AudioFileManager
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    private val _recordingStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    private val _playbackState = MutableStateFlow(PlaybackState())
    private val _dialogsAndMessages = MutableStateFlow(DialogsAndMessages())

    private var recordingTimerJob: Job? = null
    private var recordingElapsedMs: Long = 0L
    private var lastResumeTimestamp: Long = 0L

    private var playbackPollingJob: Job? = null

    // Combined reactive UI State flow (exactly 5 flows for standard type-safe combine)
    val uiState: StateFlow<NotebookUiState> = combine(
        repository.getAllNotes(),
        _searchQuery,
        _recordingStatus,
        _playbackState,
        _dialogsAndMessages
    ) { allNotes, query, recStatus, playState, dm ->
        val filteredNotes = if (query.isBlank()) {
            allNotes
        } else {
            allNotes.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.tags.contains(query, ignoreCase = true)
            }
        }

        NotebookUiState(
            notes = filteredNotes,
            isLoading = false,
            searchQuery = query,
            recordingStatus = recStatus,
            playbackState = playState,
            errorMessage = dm.errorMessage,
            infoMessage = dm.infoMessage,
            isSaveDialogVisible = dm.isSaveDialogVisible,
            pendingSavedFile = dm.pendingSavedFile,
            pendingDurationMs = dm.pendingDurationMs,
            proposedTitle = dm.proposedTitle,
            editingNote = dm.editingNote,
            noteToDelete = dm.noteToDelete
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NotebookUiState(isLoading = true)
    )

    // --- Audio Recording Logic ---

    fun startRecording() {
        // Stop any active playback prior to recording
        stopPlayback()

        val newFile = audioFileManager.createOutputFile()
        val result = audioRecorder.start(newFile)

        if (result.isSuccess) {
            recordingElapsedMs = 0L
            lastResumeTimestamp = System.currentTimeMillis()
            _recordingStatus.value = RecordingStatus.Recording(0L, 0)
            startRecordingTimer()
        } else {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                errorMessage = "Failed to start recording: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun pauseRecording() {
        val result = audioRecorder.pause()
        if (result.isSuccess) {
            recordingTimerJob?.cancel()
            val sessionDuration = System.currentTimeMillis() - lastResumeTimestamp
            recordingElapsedMs += sessionDuration
            _recordingStatus.value = RecordingStatus.Paused(recordingElapsedMs)
        } else {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                errorMessage = "Failed to pause: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun resumeRecording() {
        val result = audioRecorder.resume()
        if (result.isSuccess) {
            lastResumeTimestamp = System.currentTimeMillis()
            _recordingStatus.value = RecordingStatus.Recording(recordingElapsedMs, 0)
            startRecordingTimer()
        } else {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                errorMessage = "Failed to resume: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun stopRecording() {
        recordingTimerJob?.cancel()
        if (_recordingStatus.value is RecordingStatus.Recording) {
            val sessionDuration = System.currentTimeMillis() - lastResumeTimestamp
            recordingElapsedMs += sessionDuration
        }

        val totalDuration = recordingElapsedMs

        val result = audioRecorder.stop()
        if (result.isSuccess) {
            val file = result.getOrThrow()
            _recordingStatus.value = RecordingStatus.Idle
            val defaultTitle = "Voice Note " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                isSaveDialogVisible = true,
                pendingSavedFile = file,
                pendingDurationMs = totalDuration,
                proposedTitle = defaultTitle
            )
        } else {
            _recordingStatus.value = RecordingStatus.Idle
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                errorMessage = "Recording ended: ${result.exceptionOrNull()?.message ?: "File was empty"}"
            )
        }
    }

    fun cancelRecording() {
        recordingTimerJob?.cancel()
        audioRecorder.cancel()
        _recordingStatus.value = RecordingStatus.Idle
        recordingElapsedMs = 0L
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (isActive && audioRecorder.isRecording && !audioRecorder.isPaused) {
                delay(100)
                val currentSessionDuration = System.currentTimeMillis() - lastResumeTimestamp
                val currentTotalDuration = recordingElapsedMs + currentSessionDuration
                val amp = audioRecorder.getMaxAmplitude()
                _recordingStatus.value = RecordingStatus.Recording(currentTotalDuration, amp)
            }
        }
    }

    // --- Save / Discard Dialog Actions ---

    fun confirmSaveRecording(title: String, tags: String) {
        val file = _dialogsAndMessages.value.pendingSavedFile
        val duration = _dialogsAndMessages.value.pendingDurationMs

        if (file != null) {
            viewModelScope.launch {
                try {
                    repository.saveNote(
                        title = title.ifBlank { _dialogsAndMessages.value.proposedTitle },
                        file = file,
                        durationMs = duration,
                        tags = tags
                    )
                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                        isSaveDialogVisible = false,
                        pendingSavedFile = null,
                        pendingDurationMs = 0L,
                        proposedTitle = "",
                        infoMessage = "Voice note saved successfully"
                    )
                } catch (e: Exception) {
                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                        isSaveDialogVisible = false,
                        pendingSavedFile = null,
                        pendingDurationMs = 0L,
                        proposedTitle = "",
                        errorMessage = "Failed to save note: ${e.message}"
                    )
                }
            }
        } else {
            dismissSaveDialog()
        }
    }

    fun discardPendingRecording() {
        val file = _dialogsAndMessages.value.pendingSavedFile
        if (file != null) {
            audioFileManager.deleteAudioFile(file.absolutePath)
        }
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            isSaveDialogVisible = false,
            pendingSavedFile = null,
            pendingDurationMs = 0L,
            proposedTitle = "",
            infoMessage = "Recording discarded"
        )
    }

    private fun dismissSaveDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            isSaveDialogVisible = false,
            pendingSavedFile = null,
            pendingDurationMs = 0L,
            proposedTitle = ""
        )
    }

    // --- Audio Playback Logic ---

    fun togglePlayNote(note: VoiceNote) {
        val currentPlayState = _playbackState.value

        if (currentPlayState.currentNoteId == note.id) {
            if (currentPlayState.isPlaying) {
                audioPlayer.pause()
                _playbackState.value = currentPlayState.copy(isPlaying = false)
                playbackPollingJob?.cancel()
            } else {
                audioPlayer.resume()
                _playbackState.value = currentPlayState.copy(isPlaying = true)
                startPlaybackPolling(note.id)
            }
        } else {
            // New note playback
            audioPlayer.stop()
            playbackPollingJob?.cancel()

            val file = File(note.filePath)
            if (!file.exists()) {
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    errorMessage = "Audio file not found on device storage"
                )
                return
            }

            val result = audioPlayer.play(
                file = file,
                onCompletion = {
                    _playbackState.value = PlaybackState()
                    playbackPollingJob?.cancel()
                },
                onError = { err ->
                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                        errorMessage = err
                    )
                    _playbackState.value = PlaybackState()
                    playbackPollingJob?.cancel()
                }
            )

            if (result.isSuccess) {
                _playbackState.value = PlaybackState(
                    currentNoteId = note.id,
                    isPlaying = true,
                    currentPositionMs = 0L,
                    durationMs = note.durationMs
                )
                startPlaybackPolling(note.id)
            }
        }
    }

    fun seekPlayback(positionMs: Long) {
        audioPlayer.seekTo(positionMs.toInt())
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun stopPlayback() {
        playbackPollingJob?.cancel()
        audioPlayer.stop()
        _playbackState.value = PlaybackState()
    }

    private fun startPlaybackPolling(noteId: Long) {
        playbackPollingJob?.cancel()
        playbackPollingJob = viewModelScope.launch {
            while (isActive && audioPlayer.isPlaying) {
                delay(100)
                val pos = audioPlayer.currentPosition.toLong()
                val dur = audioPlayer.duration.toLong().coerceAtLeast(1L)
                _playbackState.value = _playbackState.value.copy(
                    currentNoteId = noteId,
                    isPlaying = true,
                    currentPositionMs = pos,
                    durationMs = dur
                )
            }
        }
    }

    // --- Note Management Actions ---

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleStarred(note: VoiceNote) {
        viewModelScope.launch {
            repository.toggleStarred(note.id)
        }
    }

    fun promptEditTitle(note: VoiceNote) {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(editingNote = note)
    }

    fun confirmEditTitle(newTitle: String) {
        val note = _dialogsAndMessages.value.editingNote ?: return
        viewModelScope.launch {
            repository.updateNoteTitle(note.id, newTitle)
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                editingNote = null,
                infoMessage = "Title updated"
            )
        }
    }

    fun dismissEditTitleDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(editingNote = null)
    }

    fun promptDeleteNote(note: VoiceNote) {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(noteToDelete = note)
    }

    fun confirmDeleteNote() {
        val note = _dialogsAndMessages.value.noteToDelete ?: return
        if (_playbackState.value.currentNoteId == note.id) {
            stopPlayback()
        }
        viewModelScope.launch {
            repository.deleteNote(note)
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                noteToDelete = null,
                infoMessage = "Note and audio file deleted"
            )
        }
    }

    fun dismissDeleteDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(noteToDelete = null)
    }

    fun clearErrorMessage() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(errorMessage = null)
    }

    fun clearInfoMessage() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(infoMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        recordingTimerJob?.cancel()
        playbackPollingJob?.cancel()
        audioRecorder.cancel()
        audioPlayer.release()
    }

    companion object {
        fun provideFactory(application: Application): androidx.lifecycle.ViewModelProvider.Factory {
            return object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    val db = AppDatabase.getDatabase(application)
                    val fileManager = AudioFileManager(application)
                    val repo = VoiceNoteRepositoryImpl(db.voiceNoteDao(), fileManager)
                    val recorder = AndroidAudioRecorder(application)
                    val player = AndroidAudioPlayer(application)
                    return VoiceNoteViewModel(
                        application = application,
                        repository = repo,
                        audioRecorder = recorder,
                        audioPlayer = player,
                        audioFileManager = fileManager
                    ) as T
                }
            }
        }
    }
}
