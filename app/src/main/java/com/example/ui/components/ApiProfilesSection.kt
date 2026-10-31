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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.model.AiProviderType
import com.example.ai.model.ApiProfile
import com.example.ai.model.ProfileQuotaConfig
import com.example.ai.model.QuotaResetPeriod
import com.example.ai.model.QuotaUnit

/**
 * Section for managing unlimited API profiles (multiple keys for same provider,
 * custom models, endpoints, Keystore credential entry, and quota configuration).
 */
@Composable
fun ApiProfilesSection(
    profiles: List<ApiProfile>,
    getMaskedKey: (ApiProfile) -> String,
    onSaveProfile: (ApiProfile, String?) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTestProfile: (ApiProfile, (Result<String>) -> Unit) -> Unit
) {
    var isAddingProfile by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ApiProfile?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "API PROFILES",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Manage unlimited keys, providers, models, and fallback chains.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    editingProfile = null
                    isAddingProfile = true
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("add_profile_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Profile", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Profile Editor Form (Add or Edit)
        AnimatedVisibility(visible = isAddingProfile || editingProfile != null) {
            val initial = editingProfile ?: ApiProfile(
                name = "New Profile",
                provider = AiProviderType.GEMINI,
                model = "gemini-3.5-flash"
            )
            ProfileEditorCard(
                initialProfile = initial,
                isNew = isAddingProfile,
                onSave = { savedProfile, key ->
                    onSaveProfile(savedProfile, key)
                    isAddingProfile = false
                    editingProfile = null
                },
                onCancel = {
                    isAddingProfile = false
                    editingProfile = null
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // List of Profiles
        profiles.forEach { profile ->
            ProfileListItemCard(
                profile = profile,
                maskedKey = getMaskedKey(profile),
                onToggleEnabled = { enabled ->
                    onSaveProfile(profile.copy(isEnabled = enabled), null)
                },
                onEdit = {
                    isAddingProfile = false
                    editingProfile = profile
                },
                onDelete = {
                    onDeleteProfile(profile.id)
                },
                onTest = onTestProfile
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileListItemCard(
    profile: ApiProfile,
    maskedKey: String,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: (ApiProfile, (Result<String>) -> Unit) -> Unit
) {
    var isTesting by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testIsSuccess by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (profile.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = profile.provider.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Model: ${profile.model}${if (profile.customEndpoint.isNotBlank()) " • Custom URL" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Key: $maskedKey",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Switch(
                    checked = profile.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("toggle_profile_${profile.id}")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Actions row: Test, Edit, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            testResultText = null
                            onTest(profile) { result ->
                                isTesting = false
                                testIsSuccess = result.isSuccess
                                testResultText = result.fold(
                                    onSuccess = { it },
                                    onFailure = { it.message ?: "Connection failed" }
                                )
                            }
                        },
                        enabled = !isTesting && profile.isEnabled,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("test_profile_button_${profile.id}")
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Test", style = MaterialTheme.typography.labelSmall)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("edit_profile_button_${profile.id}")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_profile_button_${profile.id}")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Profile",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            testResultText?.let { msg ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (testIsSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (testIsSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileEditorCard(
    initialProfile: ApiProfile,
    isNew: Boolean,
    onSave: (ApiProfile, String?) -> Unit,
    onCancel: () -> Unit
) {
    var nameInput by remember { mutableStateOf(initialProfile.name) }
    var providerSelected by remember { mutableStateOf(initialProfile.provider) }
    var modelInput by remember { mutableStateOf(initialProfile.model) }
    var endpointInput by remember { mutableStateOf(initialProfile.customEndpoint) }
    var apiKeyInput by remember { mutableStateOf("") }

    var hasManualLimit by remember { mutableStateOf(initialProfile.quotaConfig.hasManualLimit) }
    var limitInput by remember { mutableStateOf(if (initialProfile.quotaConfig.limit > 0) initialProfile.quotaConfig.limit.toString() else "") }
    var selectedUnit by remember { mutableStateOf(initialProfile.quotaConfig.unit) }
    var selectedPeriod by remember { mutableStateOf(initialProfile.quotaConfig.resetPeriod) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_editor_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = if (isNew) "Add API Profile" else "Edit API Profile",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Profile Name") },
                placeholder = { Text("e.g. Work Gemini, Groq Whisper") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_name_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Provider selection
            Text(
                text = "Provider",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiProviderType.values().forEach { prov ->
                    val selected = providerSelected == prov
                    FilterChip(
                        selected = selected,
                        onClick = {
                            providerSelected = prov
                            if (modelInput == "gemini-3.5-flash" && prov == AiProviderType.OPENAI_COMPATIBLE) {
                                modelInput = "gpt-4o-mini"
                            } else if (modelInput == "gpt-4o-mini" && prov == AiProviderType.GEMINI) {
                                modelInput = "gemini-3.5-flash"
                            }
                        },
                        label = { Text(prov.displayName) },
                        modifier = Modifier.testTag("provider_chip_${prov.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("API Key (Stored locally in KeyStore)") },
                placeholder = { Text(if (isNew) "Enter API key" else "Leave blank to keep existing key") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_key_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                label = { Text("Model Name") },
                placeholder = { Text("gemini-3.5-flash, gpt-4o-mini, whisper-1") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_model_input")
            )

            if (providerSelected == AiProviderType.OPENAI_COMPATIBLE) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = endpointInput,
                    onValueChange = { endpointInput = it },
                    label = { Text("Custom Base URL (Optional)") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_endpoint_input")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))

            // Quota Limits & Reset Configuration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Configure Manual Quota Limit",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Enables progress dashboard and automatic rate limit prevention.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = hasManualLimit,
                    onCheckedChange = { hasManualLimit = it },
                    modifier = Modifier.testTag("has_manual_limit_switch")
                )
            }

            if (hasManualLimit) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = limitInput,
                    onValueChange = { limitInput = it.filter { char -> char.isDigit() } },
                    label = { Text("Limit Amount") },
                    placeholder = { Text("e.g. 1500") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quota_limit_input")
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unit",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuotaUnit.values().forEach { u ->
                        FilterChip(
                            selected = selectedUnit == u,
                            onClick = { selectedUnit = u },
                            label = { Text(u.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reset Period",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuotaResetPeriod.values().forEach { p ->
                        FilterChip(
                            selected = selectedPeriod == p,
                            onClick = { selectedPeriod = p },
                            label = { Text(p.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Save / Cancel row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val parsedLimit = limitInput.toLongOrNull() ?: 0L
                        val updatedProfile = initialProfile.copy(
                            name = nameInput.ifBlank { "Profile" },
                            provider = providerSelected,
                            model = modelInput.ifBlank { "gemini-3.5-flash" },
                            customEndpoint = endpointInput.trim(),
                            quotaConfig = ProfileQuotaConfig(
                                hasManualLimit = hasManualLimit,
                                limit = parsedLimit,
                                unit = selectedUnit,
                                resetPeriod = selectedPeriod
                            )
                        )
                        val keyToSave = apiKeyInput.trim().ifBlank { null }
                        onSave(updatedProfile, keyToSave)
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("save_profile_button")
                ) {
                    Text("Save Profile")
                }
            }
        }
    }
}
