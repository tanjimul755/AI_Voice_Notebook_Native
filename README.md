# AI Voice Notebook

A native Android application built with Kotlin and Jetpack Compose for recording, persisting, playing back, and managing audio voice notes locally. Designed as a foundational university Software Engineering milestone with Clean Architecture, high testability, and zero reliance on paid external cloud services.

---

## 1. Project Overview & Current Feature Status

### Current Feature Status (Milestone 1 — Completed)
- [x] **Native Audio Recording:** Records uncompressed audio encoded in MPEG-4 container with AAC compression at 44.1 kHz / 128 kbps using Android's native `MediaRecorder` API.
- [x] **Pause & Resume Support:** Full pause and resume capability during recording sessions (Android 7.0+ / API 24+).
- [x] **Stop & Review / Save Dialog:** Allows naming recordings, inspecting audio duration and file size, and assigning custom category tags.
- [x] **Notebook List View:** Reactive listing ordered by starred priority and chronological timestamp, rendering metadata (duration, file size, creation timestamp, tags).
- [x] **In-App Audio Playback:** Built with Android's native `MediaPlayer`, featuring play, pause, resume, interactive scrub/seek slider, and real-time playback timers.
- [x] **Persistent Local Storage:** Audio binary files (`.m4a`) are saved directly to private internal storage (`context.filesDir/voice_notes`). Metadata is stored in a SQLite database via Room.
- [x] **Guaranteed App-Restart Persistence:** Notes and audio references survive app terminations, process kills, and device restarts.
- [x] **Safe Physical File Deletion:** Deleting a note purges both its Room metadata entry and the physical `.m4a` file on disk.
- [x] **Permission Lifecycle Handling:** Manages runtime `RECORD_AUDIO` permission requests, rationale display, and fallback to system app settings.
- [x] **State Feedback & Robustness:** Handled empty state, loading state, error banners, and recording cancellation.
- [x] **Unit & Robolectric Test Suite:** Automated local JVM tests verifying Room DAO operations, repository CRUD, file system utilities, and string resources.

---

## 2. Technology Stack & Architecture

### Technology Stack
- **Language:** Kotlin 100%
- **UI Framework:** Jetpack Compose (Material Design 3)
- **Local Persistence:** Room Database (SQLite abstraction with KSP) + Internal File Storage
- **Audio APIs:** Android native `android.media.MediaRecorder` & `android.media.MediaPlayer`
- **Asynchronous Concurrency:** Kotlin Coroutines & reactive `Flow` / `StateFlow`
- **Architecture:** Clean Architecture / Model-View-ViewModel (MVVM)
- **Testing:** JUnit4, Robolectric, AndroidX Test

### Project Architecture & Directory Layout

```
app/src/main/java/com/example/
├── MainActivity.kt                  # Activity entry point with Edge-to-Edge support
│
├── model/                           # Pure Domain Models
│   └── VoiceNote.kt                 # Business model for voice notes
│
├── data/                            # Persistence & Data Layer
│   ├── VoiceNoteEntity.kt           # Room SQLite Entity table definition
│   ├── VoiceNoteDao.kt              # Room Data Access Object with reactive Flows
│   ├── AppDatabase.kt               # Singleton Room Database configuration
│   └── VoiceNoteRepository.kt       # Repository contract & implementation
│
├── audio/                           # Audio Hardware & File Management
│   ├── AudioRecorder.kt             # Recorder abstraction interface
│   ├── AndroidAudioRecorder.kt      # Native MediaRecorder implementation (AAC/M4A)
│   ├── AudioPlayer.kt               # Player abstraction interface
│   ├── AndroidAudioPlayer.kt        # Native MediaPlayer implementation
│   └── AudioFileManager.kt          # Disk path resolver, formatters, and cleanup
│
└── ui/                              # Presentation Layer (Jetpack Compose)
    ├── VoiceNoteViewModel.kt        # MVVM ViewModel coordinating state & timers
    ├── VoiceNotebookScreen.kt       # Primary Compose screen with permission flow
    ├── components/
    │   ├── AudioWaveformView.kt     # Live amplitude-reactive waveform visualizer
    │   ├── RecordingBottomBar.kt    # Pinned recording panel (Idle/Active/Paused)
    │   ├── NoteItemCard.kt          # Note card with inline playback & scrub slider
    │   ├── SaveNoteDialog.kt        # Post-recording naming and tagging dialog
    │   ├── EditTitleDialog.kt       # Rename existing note dialog
    │   ├── DeleteConfirmationDialog.kt # Safe deletion prompt
    │   └── PermissionRationaleCard.kt  # Permission explanation and settings link
    └── theme/                       # Design System & Theming
        ├── Color.kt                 # Material 3 color definitions
        ├── Theme.kt                 # Dynamic & custom Light/Dark color schemes
        └── Type.kt                  # Typography scale
```

---

## 3. Permissions & Local Storage Approach

### Permissions
1. **`android.permission.RECORD_AUDIO`:**
   - Declared in `AndroidManifest.xml`.
   - Requested dynamically at runtime in Compose via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`.
   - If denied, a dedicated `PermissionRationaleCard` is shown with a 1-tap "Grant Permission" button and an "Open Settings" link targeting `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

2. **Zero Storage Permissions Required:**
   - Instead of writing to shared public directories (which require dangerous storage permissions or SAF), recordings are saved directly in app-private internal storage: `context.filesDir + "/voice_notes"`.
   - Benefits:
     - Completely secure and sandboxed from other apps.
     - Never expires or gets cleared by system cache cleaners.
     - Complies with modern Android storage policies without requiring runtime storage permissions.

---

## 4. Setup & Build Instructions

### Prerequisites
- JDK 11 or JDK 17
- Android SDK with Platform 36 and Build-Tools installed

### Build and Test via Command Line
```bash
# Build the Debug APK
gradle :app:assembleDebug

# Run JVM Unit and Robolectric Tests
gradle :app:testDebugUnitTest

# Lint check
gradle :app:lintDebug
```

---

## 5. Extensibility & Future Roadmap

The codebase has been designed with decoupled interfaces to support future software engineering milestones without refactoring:

1. **Speech-to-Text Transcription:**
   - The `VoiceNote` model and `VoiceNoteEntity` already contain a `transcription: String?` field.
   - An on-device or native speech recognition service (`android.speech.SpeechRecognizer`) can populate this field upon recording completion.

2. **AI-Assisted Reminder Detection:**
   - The `reminderTimestamp: Long?` field is already present in the data layer.
   - When transcription is available, a local or remote NLP parser can extract due dates/times (e.g., *"Submit project report on Friday at 4 PM"*) and set reminder triggers.

3. **Scheduled Notifications:**
   - Android `WorkManager` or `AlarmManager` can query `reminderTimestamp` to schedule local notifications when voice note reminders are due.

4. **Home Screen Launcher Widget:**
   - Jetpack Glance can be connected directly to `VoiceNoteRepository.getAllNotes()` to display recent voice notes and provide a 1-tap recording shortcut right on the user's home screen.

---

## 6. Known Limitations in Milestone 1
- **Background Recording Service:** In Milestone 1, audio recording is hosted within the foreground application lifecycle. Minimizing the app while actively recording may pause the audio session depending on OS memory pressure. Future milestone: add a Foreground Service with persistent notification.
- **Microphone Hardware in Emulators:** Android emulators running on headless cloud environments may not have a physical audio input device attached; `AndroidAudioRecorder` handles hardware errors gracefully and surfaces clear feedback.
