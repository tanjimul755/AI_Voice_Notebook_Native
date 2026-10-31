package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.model.ApiProfile
import com.example.ai.model.ProfileQuotaStatus
import com.example.ai.model.AiProviderType
import com.example.ai.model.AiTask
import com.example.ai.model.AiUsageRecord
import com.example.ai.model.TaskAiConfig
import com.example.data.NotebookSettings
import com.example.data.TranscriptionMode
import com.example.transcription.GeminiTranscriptionFormat
import kotlinx.coroutines.launch

/**
 * Settings dialog for configuring Full AI Mode, flexible API profiles,
 * task-specific routing, quota usage dashboard, and automations.
 */
@Composable
fun SettingsDialog(
    settings: NotebookSettings,
    isGeminiConfigured: Boolean,
    isWhisperModelAvailable: Boolean,
    usageRecords: List<AiUsageRecord> = emptyList(),
    profileQuotaStatuses: List<ProfileQuotaStatus> = emptyList(),
    getMaskedKey: (AiProviderType) -> String = { "Configured" },
    getMaskedProfileKey: (ApiProfile) -> String = { "Configured" },
    onSaveApiKey: (AiProviderType, String) -> Unit = { _, _ -> },
    onRemoveApiKey: (AiProviderType) -> Unit = {},
    onSaveProfile: (ApiProfile, String?) -> Unit = { _, _ -> },
    onDeleteProfile: (String) -> Unit = {},
    onTestProfile: (ApiProfile, (Result<String>) -> Unit) -> Unit = { _, _ -> },
    onTestConnection: (TaskAiConfig, (Result<String>) -> Unit) -> Unit = { _, _ -> },
    onResetUsage: () -> Unit = {},
    onFullAiModeChange: (Boolean) -> Unit = {},
    onTaskConfigChange: (TaskAiConfig) -> Unit = {},
    onTranscriptionModeChange: (TranscriptionMode) -> Unit,
    onAutoTitleChange: (Boolean) -> Unit,
    onAutoTranscribeChange: (Boolean) -> Unit,
    onFormatChange: (GeminiTranscriptionFormat) -> Unit,
    onReminderDetectionChange: (Boolean) -> Unit,
    onReminderSchedulingChange: (Boolean) -> Unit,
    onDownloadWhisperModel: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var selectedTaskIndex by remember { mutableIntStateOf(0) }
    val tasks = listOf(AiTask.TRANSCRIPTION, AiTask.REMINDER_INTERPRETATION, AiTask.TRANSCRIPT_FORMATTING)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
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
                            text = "AI Providers, Credentials & Automation",
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

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(14.dp))

                // ==========================================
                // SECTION 1: FULL AI MODE (Master Toggle)
                // ==========================================
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("full_ai_mode_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.fullAiMode) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        }
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (settings.fullAiMode) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (settings.fullAiMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Full AI Mode",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (settings.fullAiMode) "Cloud AI enabled across configured tasks" else "Offline-first; automatic cloud requests paused",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.fullAiMode,
                                onCheckedChange = onFullAiModeChange,
                                modifier = Modifier.testTag("full_ai_mode_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        ) {
                            Text(
                                text = "• Local Mode (Offline): Core audio recording, storage, and playback remain 100% on device. Speech recognition uses local Whisper (if downloaded). No automatic cloud requests.\n" +
                                       "• Full AI Mode: Enables configured cloud AI providers (Gemini / OpenAI) for automatic transcription, smart reminder interpretation, and transcript formatting. Your task settings are always authoritative.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ==========================================
                // SECTION 2: TASK-SPECIFIC AI PROVIDER CONFIG
                // ==========================================
                Text(
                    text = "TASK-SPECIFIC AI PROVIDERS",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Configure different providers and models independently for each AI task.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )

                TabRow(
                    selectedTabIndex = selectedTaskIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    tasks.forEachIndexed { index, task ->
                        Tab(
                            selected = selectedTaskIndex == index,
                            onClick = { selectedTaskIndex = index },
                            text = {
                                Text(
                                    text = when (task) {
                                        AiTask.TRANSCRIPTION -> "Audio"
                                        AiTask.REMINDER_INTERPRETATION -> "Reminders"
                                        AiTask.TRANSCRIPT_FORMATTING -> "Formatting"
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            },
                            modifier = Modifier.testTag("task_tab_${task.id}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val activeTask = tasks[selectedTaskIndex]
                val currentTaskConfig = when (activeTask) {
                    AiTask.TRANSCRIPTION -> settings.transcriptionAiConfig
                    AiTask.REMINDER_INTERPRETATION -> settings.reminderAiConfig
                    AiTask.TRANSCRIPT_FORMATTING -> settings.formattingAiConfig
                }

                TaskConfigEditor(
                    config = currentTaskConfig,
                    availableProfiles = settings.apiProfiles,
                    isFullAiMode = settings.fullAiMode,
                    onConfigChange = onTaskConfigChange,
                    onTestConnection = onTestConnection
                )

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // SECTION 3: API PROFILES & CREDENTIALS
                // ==========================================
                Text(
                    text = "API PROFILES & CREDENTIALS",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Configure unlimited profiles with multiple keys per provider, custom endpoints, and encrypted storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )

                ApiProfilesSection(
                    profiles = settings.apiProfiles,
                    getMaskedKey = getMaskedProfileKey,
                    onSaveProfile = onSaveProfile,
                    onDeleteProfile = onDeleteProfile,
                    onTestProfile = onTestProfile
                )

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // SECTION 4: QUOTA USAGE DASHBOARD
                // ==========================================
                Text(
                    text = "QUOTA USAGE DASHBOARD",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Monitor consumed and remaining quotas per profile with automatic rate limit failover tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )

                QuotaDashboardCard(
                    statuses = profileQuotaStatuses,
                    onResetUsage = onResetUsage
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Secondary Usage Tracker
                UsageSummaryCard(
                    usageRecords = usageRecords,
                    onResetUsage = onResetUsage
                )

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // SECTION 5: AUTOMATIC TRANSCRIPTION MODES
                // ==========================================
                SettingSwitchRow(
                    icon = Icons.Default.Subtitles,
                    title = "Automatic Transcription",
                    subtitle = "Transcribe recordings automatically when recording stops.",
                    checked = settings.autoTranscription,
                    onCheckedChange = onAutoTranscribeChange,
                    testTag = "setting_switch_auto_transcribe"
                )

                if (settings.autoTranscription) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "AUTOMATIC TRANSCRIPTION MODE",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    TranscriptionMode.values().forEach { mode ->
                        val isSelected = settings.transcriptionMode == mode
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onTranscriptionModeChange(mode) }
                                .testTag("transcription_mode_${mode.id}"),
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
                                    onClick = { onTranscriptionModeChange(mode) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (!isWhisperModelAvailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onDownloadWhisperModel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("download_model_settings_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download Whisper Model (39 MB)")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "DEFAULT TRANSCRIPTION FORMAT",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    GeminiTranscriptionFormat.values().forEach { format ->
                        val isSelected = settings.defaultTranscriptionFormat == format
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onFormatChange(format) }
                                .testTag("format_option_${format.id}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                }
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // SECTION 6: SMART AUTOMATIONS
                // ==========================================
                Text(
                    text = "SMART AUTOMATION",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                SettingSwitchRow(
                    icon = Icons.Default.Title,
                    title = "Automatic Title Generation",
                    subtitle = "Derives a contextual title from the transcript instead of a generic timestamp.",
                    checked = settings.autoTitleGeneration,
                    onCheckedChange = onAutoTitleChange,
                    testTag = "setting_switch_auto_title"
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    icon = Icons.Default.FormatQuote,
                    title = "Automatic Reminder Detection",
                    subtitle = "Identifies reminder requests in English, Bangla, and Banglish.",
                    checked = settings.autoReminderDetection,
                    onCheckedChange = onReminderDetectionChange,
                    testTag = "setting_switch_reminder_detection"
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    icon = Icons.Default.Alarm,
                    title = "Automatic Reminder Scheduling",
                    subtitle = "Schedules an alarm automatically when a valid explicit future time is detected.",
                    checked = settings.autoReminderScheduling,
                    onCheckedChange = onReminderSchedulingChange,
                    enabled = settings.autoReminderDetection,
                    testTag = "setting_switch_reminder_scheduling"
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

/**
 * Task-specific editor for configuring provider, model, endpoint, preferred profile, and fallback list.
 */
@Composable
private fun TaskConfigEditor(
    config: TaskAiConfig,
    availableProfiles: List<ApiProfile>,
    isFullAiMode: Boolean,
    onConfigChange: (TaskAiConfig) -> Unit,
    onTestConnection: (TaskAiConfig, (Result<String>) -> Unit) -> Unit
) {
    var modelInput by remember(config) { mutableStateOf(config.model) }
    var endpointInput by remember(config) { mutableStateOf(config.customEndpoint) }
    var isTesting by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testIsSuccess by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_config_card_${config.task.id}"),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Task Enable Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Cloud AI for ${config.task.displayName}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (!config.enabled) "Disabled (Task runs locally / offline)"
                        else if (!isFullAiMode) "Allowed on demand (Full AI Mode is off)"
                        else "Active in Full AI Mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                    modifier = Modifier.testTag("task_enabled_switch_${config.task.id}")
                )
            }

            AnimatedVisibility(visible = config.enabled) {
                Column {
                    // PREFERRED PROFILE SELECTION
                    if (availableProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "PREFERRED API PROFILE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Primary API profile used for ${config.task.displayName}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        // Default / None option
                        val isDefaultSelected = config.preferredProfileId == null
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onConfigChange(config.copy(preferredProfileId = null))
                                }
                                .testTag("preferred_profile_default_${config.task.id}"),
                            color = if (isDefaultSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isDefaultSelected,
                                    onClick = { onConfigChange(config.copy(preferredProfileId = null)) }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("Automatic / Default Provider", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                    Text("Use provider and model configured below", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // Each profile option
                        availableProfiles.forEach { profile ->
                            val isSelected = config.preferredProfileId == profile.id
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onConfigChange(
                                            config.copy(
                                                preferredProfileId = profile.id,
                                                provider = profile.provider,
                                                model = profile.model,
                                                customEndpoint = profile.customEndpoint
                                            )
                                        )
                                        modelInput = profile.model
                                        endpointInput = profile.customEndpoint
                                    }
                                    .testTag("preferred_profile_${config.task.id}_${profile.id}"),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            onConfigChange(
                                                config.copy(
                                                    preferredProfileId = profile.id,
                                                    provider = profile.provider,
                                                    model = profile.model,
                                                    customEndpoint = profile.customEndpoint
                                                )
                                            )
                                            modelInput = profile.model
                                            endpointInput = profile.customEndpoint
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(profile.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                            if (!profile.isEnabled) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("(Disabled)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                        Text("${profile.provider.displayName} • ${profile.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        // FALLBACK PROFILES SELECTION
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "ORDERED FALLBACK LIST",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "If preferred profile hits rate limits (429) or runs out of quota, automatically failover to these profiles in order.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        val eligibleFallbacks = availableProfiles.filter { it.id != config.preferredProfileId }
                        if (eligibleFallbacks.isEmpty()) {
                            Text(
                                "No additional profiles available for fallback. Add more profiles below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            eligibleFallbacks.forEach { profile ->
                                val fallbackIndex = config.fallbackProfileIds.indexOf(profile.id)
                                val isFallback = fallbackIndex >= 0

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val newFallbacks = if (isFallback) {
                                                config.fallbackProfileIds.filter { it != profile.id }
                                            } else {
                                                config.fallbackProfileIds + profile.id
                                            }
                                            onConfigChange(config.copy(fallbackProfileIds = newFallbacks))
                                        }
                                        .testTag("fallback_profile_${config.task.id}_${profile.id}"),
                                    color = if (isFallback) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isFallback,
                                            onCheckedChange = { checked ->
                                                val newFallbacks = if (checked) {
                                                    if (!config.fallbackProfileIds.contains(profile.id)) {
                                                        config.fallbackProfileIds + profile.id
                                                    } else config.fallbackProfileIds
                                                } else {
                                                    config.fallbackProfileIds.filter { it != profile.id }
                                                }
                                                onConfigChange(config.copy(fallbackProfileIds = newFallbacks))
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(profile.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                                if (isFallback) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primary
                                                    ) {
                                                        Text(
                                                            "Priority #${fallbackIndex + 1}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text("${profile.provider.displayName} • ${profile.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "MANUAL PROVIDER & MODEL OVERRIDE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AiProviderType.values().forEach { provider ->
                            val isSelected = config.provider == provider
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val newModel = TaskAiConfig.getDefaultModel(config.task, provider)
                                        modelInput = newModel
                                        onConfigChange(
                                            config.copy(
                                                provider = provider,
                                                model = newModel
                                            )
                                        )
                                    }
                                    .testTag("provider_choice_${config.task.id}_${provider.id}"),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    }
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            val newModel = TaskAiConfig.getDefaultModel(config.task, provider)
                                            modelInput = newModel
                                            onConfigChange(config.copy(provider = provider, model = newModel))
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = provider.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Model name field
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = {
                            modelInput = it
                            onConfigChange(config.copy(model = it))
                        },
                        label = { Text("Model Name") },
                        placeholder = { Text(TaskAiConfig.getDefaultModel(config.task, config.provider)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("model_input_${config.task.id}")
                    )

                    if (config.provider == AiProviderType.OPENAI_COMPATIBLE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = endpointInput,
                            onValueChange = {
                                endpointInput = it
                                onConfigChange(config.copy(customEndpoint = it))
                            },
                            label = { Text("Custom Base URL (Optional)") },
                            placeholder = { Text("https://api.openai.com/v1") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("endpoint_input_${config.task.id}")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Test connection button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                isTesting = true
                                testResultText = null
                                onTestConnection(config) { result ->
                                    isTesting = false
                                    testIsSuccess = result.isSuccess
                                    testResultText = result.fold(
                                        onSuccess = { it },
                                        onFailure = { it.message ?: "Connection failed" }
                                    )
                                }
                            },
                            enabled = !isTesting,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("test_connection_button_${config.task.id}")
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Test Configuration")
                        }

                        testResultText?.let { msg ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (testIsSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .weight(1f, fill = false)
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (testIsSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card for viewing masked API key and editing/removing user-configured keys.
 */
@Composable
private fun ApiKeyCard(
    provider: AiProviderType,
    maskedKey: String,
    onSaveKey: (String) -> Unit,
    onRemoveKey: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("api_key_card_${provider.id}"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${provider.displayName} API Key",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = maskedKey,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = !isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            isEditing = true
                            keyInput = ""
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("edit_key_button_${provider.id}")
                    ) {
                        Text("Update Key")
                    }
                    if (maskedKey != "Not Configured") {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onRemoveKey,
                            modifier = Modifier.testTag("remove_key_button_${provider.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove Key", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isEditing) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("Enter ${provider.displayName} API Key") },
                        placeholder = { Text("Paste your API key here") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("key_input_field_${provider.id}")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { isEditing = false },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (keyInput.isNotBlank()) {
                                    onSaveKey(keyInput.trim())
                                    isEditing = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("save_key_button_${provider.id}")
                        ) {
                            Text("Save Key")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact usage tracker summary table and reset action.
 */
@Composable
private fun UsageSummaryCard(
    usageRecords: List<AiUsageRecord>,
    onResetUsage: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("usage_summary_card"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (usageRecords.isEmpty()) {
                Text(
                    text = "No AI requests recorded yet. Requests made by this app will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                usageRecords.forEach { record ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${record.task.displayName} • ${record.provider.displayName}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Success: ${record.successCount} | Failed: ${record.failureCount} | Quota blocked: ${record.quotaBlockedCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (record.totalTokens > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        text = "${record.totalTokens} tokens",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Local counts may differ from provider billing.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onResetUsage,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("reset_usage_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset")
                    }
                }
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
