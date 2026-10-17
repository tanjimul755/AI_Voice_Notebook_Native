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
import com.example.data.NotebookPreferences
import com.example.data.NotebookSettings
import com.example.data.VoiceNoteRepository
import com.example.data.VoiceNoteRepositoryImpl
import com.example.model.VoiceNote
import com.example.reminder.ReminderAction
import com.example.reminder.ReminderManager
import com.example.transcription.AudioTranscriber
import com.example.transcription.GeminiAudioTranscriber
import com.example.transcription.GeminiTranscriptionFormat
import com.example.transcription.WhisperAudioTranscriber
import com.example.transcription.WhisperModelStatus
import com.example.util.TitleGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val noteToDelete: VoiceNote? = null,
    val transcribingNoteId: Long? = null,
    val activeTranscribeDialogNote: VoiceNote? = null,
    val modelStatus: WhisperModelStatus? = null,
    val isDownloadingModel: Boolean = false,
    val modelDownloadProgress: Float? = null,
    val aiTranscribingNoteId: Long? = null,
    val activeAiTranscribeDialogNote: VoiceNote? = null,
    val activeReminderDialogNote: VoiceNote? = null,
    val isGeminiApiKeyConfigured: Boolean = false,
    val isSettingsDialogVisible: Boolean = false,
    val newlyCreatedNoteId: Long? = null,
    val settings: NotebookSettings = NotebookSettings()
)

private data class DialogsAndMessages(
    val isSaveDialogVisible: Boolean = false,
    val pendingSavedFile: File? = null,
    val pendingDurationMs: Long = 0L,
    val proposedTitle: String = "",
    val editingNote: VoiceNote? = null,
    val noteToDelete: VoiceNote? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val transcribingNoteId: Long? = null,
    val activeTranscribeDialogNote: VoiceNote? = null,
    val modelStatus: WhisperModelStatus? = null,
    val isDownloadingModel: Boolean = false,
    val modelDownloadProgress: Float? = null,
    val aiTranscribingNoteId: Long? = null,
    val activeAiTranscribeDialogNote: VoiceNote? = null,
    val activeReminderDialogNote: VoiceNote? = null,
    val isSettingsDialogVisible: Boolean = false,
    val newlyCreatedNoteId: Long? = null,
    val settings: NotebookSettings = NotebookSettings()
)

class VoiceNoteViewModel(
    application: Application,
    private val repository: VoiceNoteRepository,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val audioFileManager: AudioFileManager,
    private val audioTranscriber: AudioTranscriber = WhisperAudioTranscriber(application),
    private val geminiTranscriber: GeminiAudioTranscriber = GeminiAudioTranscriber(application),
    private val notebookPreferences: NotebookPreferences = NotebookPreferences(application),
    private val reminderManager: ReminderManager = ReminderManager(application)
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    private val _recordingStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    private val _playbackState = MutableStateFlow(PlaybackState())
    private val _dialogsAndMessages = MutableStateFlow(DialogsAndMessages(settings = notebookPreferences.settings.value))

    private var recordingTimerJob: Job? = null
    private var recordingElapsedMs: Long = 0L
    private var lastResumeTimestamp: Long = 0L

    private var playbackPollingJob: Job? = null

    private val transcriptionMutex = Mutex()
    private var preferredLanguage: String = "auto"

    init {
        viewModelScope.launch {
            notebookPreferences.settings.collect { newSettings ->
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(settings = newSettings)
            }
        }
    }

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
                it.tags.contains(query, ignoreCase = true) ||
                (it.transcription?.contains(query, ignoreCase = true) == true)
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
            noteToDelete = dm.noteToDelete,
            transcribingNoteId = dm.transcribingNoteId,
            activeTranscribeDialogNote = dm.activeTranscribeDialogNote,
            modelStatus = dm.modelStatus,
            isDownloadingModel = dm.isDownloadingModel,
            modelDownloadProgress = dm.modelDownloadProgress,
            aiTranscribingNoteId = dm.aiTranscribingNoteId,
            activeAiTranscribeDialogNote = dm.activeAiTranscribeDialogNote,
            activeReminderDialogNote = dm.activeReminderDialogNote,
            isGeminiApiKeyConfigured = geminiTranscriber.isApiKeyConfigured(),
            isSettingsDialogVisible = dm.isSettingsDialogVisible,
            newlyCreatedNoteId = dm.newlyCreatedNoteId,
            settings = dm.settings
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NotebookUiState(isLoading = true)
    )

    // --- Audio Recording Logic ---

    fun startRecording() {
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

    /**
     * Stops audio recording and immediately saves the audio to persistent storage.
     * No modal title/tag dialog blocks the user. The audio is safely stored before any
     * transcription or AI processing, ensuring failures never destroy a note.
     */
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
            recordingElapsedMs = 0L

            val defaultTitle = "Voice Note " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())

            viewModelScope.launch {
                try {
                    // 1. Immediately persist the recording to Room
                    val savedNote = repository.saveNote(
                        title = defaultTitle,
                        file = file,
                        durationMs = totalDuration,
                        tags = ""
                    )

                    // 2. Update UI: trigger auto-scroll to the newest note and show confirmation
                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                        isSaveDialogVisible = false,
                        pendingSavedFile = null,
                        pendingDurationMs = 0L,
                        proposedTitle = "",
                        newlyCreatedNoteId = savedNote.id,
                        infoMessage = "Voice note saved."
                    )

                    // 3. Initiate automatic transcription workflow if enabled in settings
                    val currentSettings = _dialogsAndMessages.value.settings
                    if (currentSettings.autoTranscription) {
                        processAutomaticTranscription(savedNote)
                    }
                } catch (e: Exception) {
                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                        errorMessage = "Failed to save voice note: ${e.message}"
                    )
                }
            }
        } else {
            _recordingStatus.value = RecordingStatus.Idle
            recordingElapsedMs = 0L
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

    // --- Save / Discard Dialog Actions (Maintained for backward compatibility) ---

    fun confirmSaveRecording(title: String, tags: String) {
        val file = _dialogsAndMessages.value.pendingSavedFile
        val duration = _dialogsAndMessages.value.pendingDurationMs

        if (file != null) {
            viewModelScope.launch {
                try {
                    val savedNote = repository.saveNote(
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
                        newlyCreatedNoteId = savedNote.id,
                        infoMessage = "Voice note saved."
                    )

                    if (_dialogsAndMessages.value.settings.autoTranscription) {
                        processAutomaticTranscription(savedNote)
                    }
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

    fun clearNewlyCreatedNoteId() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(newlyCreatedNoteId = null)
    }

    // --- Settings Dialog & Persistence Actions ---

    fun showSettingsDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(isSettingsDialogVisible = true)
    }

    fun dismissSettingsDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(isSettingsDialogVisible = false)
    }

    fun setAutoTitleGeneration(enabled: Boolean) {
        notebookPreferences.setAutoTitleGeneration(enabled)
    }

    fun setAutoTranscription(enabled: Boolean) {
        notebookPreferences.setAutoTranscription(enabled)
    }

    fun setAutoGeminiFallback(enabled: Boolean) {
        notebookPreferences.setAutoGeminiFallback(enabled)
    }

    fun setCloudAiEnabled(enabled: Boolean) {
        notebookPreferences.setCloudAiEnabled(enabled)
    }

    fun setDefaultTranscriptionFormat(format: GeminiTranscriptionFormat) {
        notebookPreferences.setDefaultTranscriptionFormat(format)
    }

    fun setAutoReminderDetection(enabled: Boolean) {
        notebookPreferences.setAutoReminderDetection(enabled)
    }

    fun setAutoReminderScheduling(enabled: Boolean) {
        notebookPreferences.setAutoReminderScheduling(enabled)
    }

    // --- Reminder Actions ---

    fun promptEditReminder(note: VoiceNote) {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(activeReminderDialogNote = note)
    }

    fun dismissReminderDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(activeReminderDialogNote = null)
    }

    fun saveReminderDetails(note: VoiceNote, task: String, timestampMs: Long?, isEnabled: Boolean) {
        viewModelScope.launch {
            if (isEnabled && timestampMs != null && timestampMs > System.currentTimeMillis()) {
                val scheduled = reminderManager.scheduleAlarm(
                    noteId = note.id,
                    title = task.ifBlank { note.title },
                    text = note.transcription ?: "Voice note reminder",
                    targetTimestampMs = timestampMs
                )
                repository.updateReminderDetails(
                    id = note.id,
                    timestamp = timestampMs,
                    task = task.ifBlank { note.title },
                    isEnabled = true,
                    status = "SCHEDULED"
                )
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    activeReminderDialogNote = null,
                    infoMessage = if (scheduled) "Reminder scheduled for ${AudioFileManager.formatTimestamp(timestampMs)}" else "Reminder saved"
                )
            } else {
                reminderManager.cancelAlarm(note.id)
                val status = if (timestampMs != null && timestampMs <= System.currentTimeMillis()) {
                    "COMPLETED"
                } else if (isEnabled && timestampMs == null) {
                    "UNSCHEDULED"
                } else {
                    "CANCELLED"
                }
                repository.updateReminderDetails(
                    id = note.id,
                    timestamp = timestampMs,
                    task = task.ifBlank { note.title },
                    isEnabled = false,
                    status = status
                )
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    activeReminderDialogNote = null,
                    infoMessage = if (!isEnabled) "Reminder disabled." else "Reminder updated."
                )
            }
        }
    }

    fun deleteReminder(note: VoiceNote) {
        viewModelScope.launch {
            reminderManager.cancelAlarm(note.id)
            repository.updateReminderDetails(note.id, null, null, false, "NONE")
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                activeReminderDialogNote = null,
                infoMessage = "Reminder removed."
            )
        }
    }

    // --- Automatic Transcription Orchestration ---

    /**
     * Intelligently orchestrates automatic transcription following recording save.
     * Uses local Whisper first where available; optionally falls back to Gemini AI when
     * local transcription fails, returns empty/hallucinatory text, or when the user requires
     * specialized Romanized Bangla or English translation formats.
     */
    private fun processAutomaticTranscription(note: VoiceNote) {
        val currentSettings = _dialogsAndMessages.value.settings
        val format = currentSettings.defaultTranscriptionFormat
        val allowCloud = currentSettings.cloudAiEnabled && geminiTranscriber.isApiKeyConfigured()
        val allowFallback = currentSettings.autoGeminiFallback && allowCloud

        val audioFile = File(note.filePath)
        if (!audioFile.exists() || audioFile.length() == 0L) return

        val whisperStatus = audioTranscriber.getModelStatus()

        if (whisperStatus.isAvailable) {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                transcribingNoteId = note.id,
                infoMessage = "Voice note saved. Transcribing locally..."
            )

            viewModelScope.launch {
                transcriptionMutex.withLock {
                    try {
                        val whisperResult = audioTranscriber.transcribe(audioFile, preferredLanguage)
                        val whisperText = whisperResult.getOrNull()?.trim()

                        val isFailedOrEmpty = whisperResult.isFailure ||
                                whisperText.isNullOrBlank() ||
                                whisperText == "[No speech detected in audio]"
                        val isHallucination = !whisperText.isNullOrBlank() && isRepetitiveLoop(whisperText)
                        val isSpecializedFormat = (format == GeminiTranscriptionFormat.ROMANIZED_BANGLA ||
                                format == GeminiTranscriptionFormat.ENGLISH)

                        val shouldUseFallback = allowFallback && (isFailedOrEmpty || isHallucination || isSpecializedFormat)

                        if (shouldUseFallback) {
                            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                transcribingNoteId = null,
                                aiTranscribingNoteId = note.id,
                                infoMessage = "Falling back to Gemini AI (${format.displayName})..."
                            )
                            val geminiResult = geminiTranscriber.transcribeAudio(audioFile, format)
                            if (geminiResult.isSuccess) {
                                val transcript = geminiResult.getOrThrow()
                                repository.updateTranscription(note.id, transcript)
                                applyTitleAndReminders(note, transcript)
                                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                    aiTranscribingNoteId = null,
                                    infoMessage = "Transcribed with Gemini AI (${format.displayName})"
                                )
                            } else {
                                // Fallback failed (e.g. rate limit, offline). If whisper had non-empty text, retain it.
                                if (!whisperText.isNullOrBlank() && whisperText != "[No speech detected in audio]") {
                                    repository.updateTranscription(note.id, whisperText)
                                    applyTitleAndReminders(note, whisperText)
                                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                        aiTranscribingNoteId = null,
                                        infoMessage = "Local transcript saved (AI fallback unavailable: ${geminiResult.exceptionOrNull()?.message})"
                                    )
                                } else {
                                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                        aiTranscribingNoteId = null,
                                        errorMessage = "Transcription failed: ${geminiResult.exceptionOrNull()?.message}"
                                    )
                                }
                            }
                        } else {
                            if (whisperResult.isSuccess && !whisperText.isNullOrBlank()) {
                                repository.updateTranscription(note.id, whisperText)
                                applyTitleAndReminders(note, whisperText)
                                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                    transcribingNoteId = null,
                                    infoMessage = "Transcription saved to note"
                                )
                            } else {
                                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                    transcribingNoteId = null,
                                    errorMessage = "Transcription failed: ${whisperResult.exceptionOrNull()?.message ?: "No speech recognized"}"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                            transcribingNoteId = null,
                            aiTranscribingNoteId = null,
                            errorMessage = "Transcription error: ${e.message}"
                        )
                    }
                }
            }
        } else {
            // Whisper model is not downloaded
            if (allowFallback) {
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    aiTranscribingNoteId = note.id,
                    infoMessage = "Whisper offline model missing. Transcribing with Gemini AI..."
                )
                viewModelScope.launch {
                    transcriptionMutex.withLock {
                        try {
                            val geminiResult = geminiTranscriber.transcribeAudio(audioFile, format)
                            if (geminiResult.isSuccess) {
                                val transcript = geminiResult.getOrThrow()
                                repository.updateTranscription(note.id, transcript)
                                applyTitleAndReminders(note, transcript)
                                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                    aiTranscribingNoteId = null,
                                    infoMessage = "AI transcription completed (${format.displayName})"
                                )
                            } else {
                                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                    aiTranscribingNoteId = null,
                                    errorMessage = "AI transcription failed: ${geminiResult.exceptionOrNull()?.message}"
                                )
                            }
                        } catch (e: Exception) {
                            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                aiTranscribingNoteId = null,
                                errorMessage = "AI transcription error: ${e.message}"
                            )
                        }
                    }
                }
            } else {
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    activeTranscribeDialogNote = note,
                    modelStatus = whisperStatus,
                    infoMessage = "Voice note saved. Offline Whisper model required for transcription."
                )
            }
        }
    }

    private suspend fun applyTitleAndReminders(note: VoiceNote, transcript: String) {
        val currentSettings = _dialogsAndMessages.value.settings

        // 1. Contextual Title Generation
        if (currentSettings.autoTitleGeneration) {
            val generatedTitle = TitleGenerator.generateTitle(transcript)
            if (!generatedTitle.isNullOrBlank()) {
                repository.updateNoteTitle(note.id, generatedTitle)
            }
        }

        // 2. Reminder Detection & Scheduling
        if (currentSettings.autoReminderDetection) {
            val reminder = reminderManager.detectReminder(transcript)
            if (reminder != null && reminder.action != ReminderAction.IGNORE) {
                when (reminder.action) {
                    ReminderAction.CANCEL -> {
                        val targetNote = reminder.targetQuery?.let { query ->
                            repository.findActiveReminderByQuery(query)
                        } ?: note
                        reminderManager.cancelAlarm(targetNote.id)
                        repository.updateReminderDetails(
                            id = targetNote.id,
                            timestamp = targetNote.reminderTimestamp,
                            task = targetNote.reminderTask,
                            isEnabled = false,
                            status = "CANCELLED"
                        )
                        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                            infoMessage = "Reminder cancelled for \"${targetNote.reminderTask ?: targetNote.title}\""
                        )
                    }
                    ReminderAction.UPDATE -> {
                        val targetNote = reminder.targetQuery?.let { query ->
                            repository.findActiveReminderByQuery(query)
                        } ?: note
                        if (reminder.targetTimestampMs != null && reminder.targetTimestampMs > System.currentTimeMillis()) {
                            reminderManager.scheduleAlarm(
                                noteId = targetNote.id,
                                title = targetNote.reminderTask ?: targetNote.title,
                                text = targetNote.transcription ?: "Voice note reminder",
                                targetTimestampMs = reminder.targetTimestampMs
                            )
                            repository.updateReminderDetails(
                                id = targetNote.id,
                                timestamp = reminder.targetTimestampMs,
                                task = targetNote.reminderTask,
                                isEnabled = true,
                                status = "SCHEDULED"
                            )
                            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                infoMessage = "Reminder rescheduled for ${AudioFileManager.formatTimestamp(reminder.targetTimestampMs)}"
                            )
                        }
                    }
                    ReminderAction.CREATE -> {
                        if (reminder.targetTimestampMs != null) {
                            if (currentSettings.autoReminderScheduling && reminder.targetTimestampMs > System.currentTimeMillis()) {
                                val scheduled = reminderManager.scheduleAlarm(
                                    noteId = note.id,
                                    title = reminder.taskSummary,
                                    text = transcript,
                                    targetTimestampMs = reminder.targetTimestampMs
                                )
                                repository.updateReminderDetails(
                                    id = note.id,
                                    timestamp = reminder.targetTimestampMs,
                                    task = reminder.taskSummary,
                                    isEnabled = true,
                                    status = "SCHEDULED"
                                )
                                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                    infoMessage = if (scheduled) "Reminder scheduled for ${AudioFileManager.formatTimestamp(reminder.targetTimestampMs)}" else "Reminder detected: ${reminder.taskSummary}"
                                )
                            } else {
                                repository.updateReminderDetails(
                                    id = note.id,
                                    timestamp = reminder.targetTimestampMs,
                                    task = reminder.taskSummary,
                                    isEnabled = false,
                                    status = "UNSCHEDULED"
                                )
                                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                    infoMessage = "Reminder detected: ${reminder.taskSummary} (Tap bell to schedule)"
                                )
                            }
                        } else {
                            // Date/time missing or ambiguous: preserve reminder intent without scheduling
                            repository.updateReminderDetails(
                                id = note.id,
                                timestamp = null,
                                task = reminder.taskSummary,
                                isEnabled = false,
                                status = "UNSCHEDULED"
                            )
                            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                                infoMessage = "Reminder detected: ${reminder.taskSummary} (Tap bell to set time)"
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun isRepetitiveLoop(text: String): Boolean {
        if (text.length < 30) return false
        val words = text.split("\\s+".toRegex())
        if (words.size < 8) return false
        for (i in 0 until words.size - 5) {
            val phrase = "${words[i]} ${words[i+1]} ${words[i+2]}"
            val count = text.windowed(phrase.length, 1).count { it == phrase }
            if (count >= 3) return true
        }
        return false
    }

    // --- Offline Speech-to-Text Transcription Actions (Manual) ---

    fun promptTranscribe(note: VoiceNote) {
        val status = audioTranscriber.getModelStatus()
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            activeTranscribeDialogNote = note,
            modelStatus = status
        )
    }

    fun dismissTranscribeDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            activeTranscribeDialogNote = null,
            modelDownloadProgress = null
        )
    }

    fun downloadWhisperModel() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            isDownloadingModel = true,
            modelDownloadProgress = 0f
        )
        viewModelScope.launch {
            val result = audioTranscriber.downloadModel { progress ->
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    modelDownloadProgress = progress
                )
            }
            if (result.isSuccess) {
                val updatedStatus = audioTranscriber.getModelStatus()
                val waitingNote = _dialogsAndMessages.value.activeTranscribeDialogNote
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    isDownloadingModel = false,
                    modelDownloadProgress = null,
                    modelStatus = updatedStatus,
                    infoMessage = "Whisper model downloaded successfully. Ready for offline transcription."
                )
                if (waitingNote != null && updatedStatus.isAvailable) {
                    startTranscribe(waitingNote, preferredLanguage)
                }
            } else {
                _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                    isDownloadingModel = false,
                    modelDownloadProgress = null,
                    errorMessage = "Failed to download model: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun startTranscribe(note: VoiceNote, language: String = preferredLanguage) {
        preferredLanguage = language

        if (_dialogsAndMessages.value.transcribingNoteId == note.id ||
            _dialogsAndMessages.value.aiTranscribingNoteId == note.id
        ) {
            return
        }

        val audioFile = File(note.filePath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                activeTranscribeDialogNote = null,
                errorMessage = "Audio file not found on device storage"
            )
            return
        }

        val status = audioTranscriber.getModelStatus()
        if (!status.isAvailable) {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                modelStatus = status,
                activeTranscribeDialogNote = note,
                errorMessage = "Offline Whisper model is not downloaded yet. Please download it first."
            )
            return
        }

        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            activeTranscribeDialogNote = null,
            transcribingNoteId = note.id,
            infoMessage = "Transcribing voice note locally... Please wait."
        )

        viewModelScope.launch {
            transcriptionMutex.withLock {
                try {
                    val result = audioTranscriber.transcribe(audioFile, language)
                    if (result.isSuccess) {
                        val transcript = result.getOrThrow()
                        repository.updateTranscription(note.id, transcript)
                        applyTitleAndReminders(note, transcript)
                        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                            transcribingNoteId = null,
                            infoMessage = "Transcription saved to note"
                        )
                    } else {
                        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                            transcribingNoteId = null,
                            errorMessage = "Transcription failed: ${result.exceptionOrNull()?.message}"
                        )
                    }
                } catch (e: Exception) {
                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                        transcribingNoteId = null,
                        errorMessage = "Transcription failed: ${e.message}"
                    )
                }
            }
        }
    }

    // --- Google Gemini AI Audio Transcription Actions (Manual) ---

    fun promptAiTranscribe(note: VoiceNote) {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            activeAiTranscribeDialogNote = note
        )
    }

    fun dismissAiTranscribeDialog() {
        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            activeAiTranscribeDialogNote = null
        )
    }

    fun startAiTranscribe(
        note: VoiceNote,
        format: GeminiTranscriptionFormat = _dialogsAndMessages.value.settings.defaultTranscriptionFormat
    ) {
        if (_dialogsAndMessages.value.aiTranscribingNoteId == note.id ||
            _dialogsAndMessages.value.transcribingNoteId == note.id
        ) {
            return
        }

        val audioFile = File(note.filePath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                activeAiTranscribeDialogNote = null,
                errorMessage = "Audio file not found on device storage"
            )
            return
        }

        if (!geminiTranscriber.isApiKeyConfigured()) {
            _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                activeAiTranscribeDialogNote = null,
                errorMessage = "Gemini API key is not configured. Please set your GEMINI_API_KEY in the Secrets panel in AI Studio."
            )
            return
        }

        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
            activeAiTranscribeDialogNote = null,
            aiTranscribingNoteId = note.id,
            infoMessage = "Transcribing with Gemini AI (${format.displayName})... Please wait."
        )

        viewModelScope.launch {
            transcriptionMutex.withLock {
                try {
                    val result = geminiTranscriber.transcribeAudio(audioFile, format)
                    if (result.isSuccess) {
                        val transcript = result.getOrThrow()
                        repository.updateTranscription(note.id, transcript)
                        applyTitleAndReminders(note, transcript)
                        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                            aiTranscribingNoteId = null,
                            infoMessage = "AI transcription completed (${format.displayName})"
                        )
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Unknown error"
                        _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                            aiTranscribingNoteId = null,
                            errorMessage = "AI transcription failed: $err"
                        )
                    }
                } catch (e: Exception) {
                    _dialogsAndMessages.value = _dialogsAndMessages.value.copy(
                        aiTranscribingNoteId = null,
                        errorMessage = "AI transcription error: ${e.message}"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recordingTimerJob?.cancel()
        playbackPollingJob?.cancel()
        audioRecorder.cancel()
        audioPlayer.release()
        audioTranscriber.release()
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
                    val transcriber = WhisperAudioTranscriber(application)
                    val geminiTranscriber = GeminiAudioTranscriber(application)
                    val preferences = NotebookPreferences(application)
                    val reminderMgr = ReminderManager(application)
                    return VoiceNoteViewModel(
                        application = application,
                        repository = repo,
                        audioRecorder = recorder,
                        audioPlayer = player,
                        audioFileManager = fileManager,
                        audioTranscriber = transcriber,
                        geminiTranscriber = geminiTranscriber,
                        notebookPreferences = preferences,
                        reminderManager = reminderMgr
                    ) as T
                }
            }
        }
    }
}
