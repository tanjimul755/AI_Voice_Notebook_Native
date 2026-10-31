package com.example.ai.usage

import android.content.Context
import android.content.SharedPreferences
import com.example.ai.model.AiProviderType
import com.example.ai.model.AiTask
import com.example.ai.model.AiUsageRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_FILE = "ai_notebook_usage_tracker"

/**
 * Tracks AI requests and token counts grouped by task and provider.
 * Allows distinguishing successes, errors, and quota-limited (429) requests.
 */
class AiUsageTracker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _usageFlow = MutableStateFlow(loadAllUsage())
    val usageFlow: StateFlow<Map<String, AiUsageRecord>> = _usageFlow.asStateFlow()

    @Synchronized
    fun recordSuccess(
        task: AiTask,
        provider: AiProviderType,
        promptTokens: Long = 0L,
        completionTokens: Long = 0L,
        totalTokens: Long = (promptTokens + completionTokens)
    ) {
        val current = getUsage(task, provider)
        val updated = current.copy(
            successCount = current.successCount + 1,
            promptTokens = current.promptTokens + promptTokens,
            completionTokens = current.completionTokens + completionTokens,
            totalTokens = current.totalTokens + totalTokens
        )
        saveUsage(updated)

        // Also track for default provider profile
        recordProfileSuccess(
            profileId = "default_${provider.id}",
            task = task,
            provider = provider,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
    }

    @Synchronized
    fun recordProfileSuccess(
        profileId: String,
        task: AiTask,
        provider: AiProviderType,
        promptTokens: Long = 0L,
        completionTokens: Long = 0L,
        totalTokens: Long = (promptTokens + completionTokens),
        audioDurationMs: Long = 0L
    ) {
        val pKey = "profile_$profileId"
        val curSuccess = prefs.getInt("${pKey}_success", 0)
        val curTokens = prefs.getLong("${pKey}_tokens", 0L)
        val curAudioMs = prefs.getLong("${pKey}_audio_ms", 0L)

        val curPeriodRequests = prefs.getLong("${pKey}_period_requests", 0L)
        val curPeriodTokens = prefs.getLong("${pKey}_period_tokens", 0L)
        val curPeriodAudioMs = prefs.getLong("${pKey}_period_audio_ms", 0L)

        prefs.edit()
            .putInt("${pKey}_success", curSuccess + 1)
            .putLong("${pKey}_tokens", curTokens + totalTokens)
            .putLong("${pKey}_audio_ms", curAudioMs + audioDurationMs)
            .putLong("${pKey}_period_requests", curPeriodRequests + 1)
            .putLong("${pKey}_period_tokens", curPeriodTokens + totalTokens)
            .putLong("${pKey}_period_audio_ms", curPeriodAudioMs + audioDurationMs)
            .apply()
    }

    @Synchronized
    fun recordFailure(
        task: AiTask,
        provider: AiProviderType,
        isQuotaBlocked: Boolean = false
    ) {
        val current = getUsage(task, provider)
        val updated = if (isQuotaBlocked) {
            current.copy(quotaBlockedCount = current.quotaBlockedCount + 1)
        } else {
            current.copy(failureCount = current.failureCount + 1)
        }
        saveUsage(updated)

        recordProfileFailure(
            profileId = "default_${provider.id}",
            task = task,
            provider = provider,
            isQuotaBlocked = isQuotaBlocked
        )
    }

    @Synchronized
    fun recordProfileFailure(
        profileId: String,
        task: AiTask,
        provider: AiProviderType,
        isQuotaBlocked: Boolean = false
    ) {
        val pKey = "profile_$profileId"
        if (isQuotaBlocked) {
            val curQuota = prefs.getInt("${pKey}_quota", 0)
            val curPeriodQuota = prefs.getInt("${pKey}_period_quota_blocked", 0)
            prefs.edit()
                .putInt("${pKey}_quota", curQuota + 1)
                .putInt("${pKey}_period_quota_blocked", curPeriodQuota + 1)
                .apply()
        } else {
            val curFail = prefs.getInt("${pKey}_failure", 0)
            val curPeriodFail = prefs.getInt("${pKey}_period_failure", 0)
            prefs.edit()
                .putInt("${pKey}_failure", curFail + 1)
                .putInt("${pKey}_period_failure", curPeriodFail + 1)
                .apply()
        }
    }

    /**
     * Calculates quota usage dashboard metrics for a given profile.
     * Accurately distinguishes between configured limits and missing/unavailable quota.
     */
    fun getProfileQuotaStatus(profile: com.example.ai.model.ApiProfile): com.example.ai.model.ProfileQuotaStatus {
        val pKey = "profile_${profile.id}"
        val now = System.currentTimeMillis()

        // Check reset period roll-over
        checkAndResetPeriodIfNeeded(profile, now)

        val successCount = prefs.getInt("${pKey}_success", 0)
        val failureCount = prefs.getInt("${pKey}_failure", 0)
        val quotaBlockedCount = prefs.getInt("${pKey}_period_quota_blocked", prefs.getInt("${pKey}_quota", 0))

        val periodRequests = prefs.getLong("${pKey}_period_requests", 0L)
        val periodTokens = prefs.getLong("${pKey}_period_tokens", 0L)
        val periodAudioMs = prefs.getLong("${pKey}_period_audio_ms", 0L)

        val unit = profile.quotaConfig.unit
        val consumed = when (unit) {
            com.example.ai.model.QuotaUnit.REQUESTS -> periodRequests
            com.example.ai.model.QuotaUnit.TOKENS -> periodTokens
            com.example.ai.model.QuotaUnit.AUDIO_MINUTES -> {
                if (periodAudioMs <= 0L) 0L else (periodAudioMs / 60_000L).coerceAtLeast(1L)
            }
            com.example.ai.model.QuotaUnit.CREDITS -> periodRequests
        }

        val hasLimit = profile.quotaConfig.limit > 0L

        return if (hasLimit) {
            val limit = profile.quotaConfig.limit
            val remaining = (limit - consumed).coerceAtLeast(0L)
            val progress = (consumed.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
            val isExhausted = consumed >= limit || quotaBlockedCount > 0
            val resetLabel = when (profile.quotaConfig.resetPeriod) {
                com.example.ai.model.QuotaResetPeriod.DAILY -> "Resets daily"
                com.example.ai.model.QuotaResetPeriod.MONTHLY -> "Resets monthly"
                com.example.ai.model.QuotaResetPeriod.TOTAL -> "Total account limit"
            }

            com.example.ai.model.ProfileQuotaStatus(
                profileId = profile.id,
                profileName = profile.name,
                provider = profile.provider,
                consumed = consumed,
                limit = limit,
                remaining = remaining,
                unit = unit,
                resetInfo = resetLabel,
                isEstimated = true,
                isQuotaUnavailable = false,
                isQuotaExhausted = isExhausted,
                progress = progress,
                successCount = successCount,
                failureCount = failureCount,
                quotaBlockedCount = quotaBlockedCount
            )
        } else {
            // No quota data configured: clearly show unavailable, never invent percentage
            val isExhausted = quotaBlockedCount > 0
            val resetLabel = if (isExhausted) "Rate limit hit (429)" else "Quota unavailable"

            com.example.ai.model.ProfileQuotaStatus(
                profileId = profile.id,
                profileName = profile.name,
                provider = profile.provider,
                consumed = consumed,
                limit = null,
                remaining = null,
                unit = unit,
                resetInfo = resetLabel,
                isEstimated = false,
                isQuotaUnavailable = true,
                isQuotaExhausted = isExhausted,
                progress = 0f,
                successCount = successCount,
                failureCount = failureCount,
                quotaBlockedCount = quotaBlockedCount
            )
        }
    }

    private fun checkAndResetPeriodIfNeeded(profile: com.example.ai.model.ApiProfile, currentTimeMs: Long) {
        val pKey = "profile_${profile.id}"
        val periodStart = prefs.getLong("${pKey}_period_start", 0L)
        if (periodStart == 0L) {
            prefs.edit().putLong("${pKey}_period_start", currentTimeMs).apply()
            return
        }

        val elapsed = currentTimeMs - periodStart
        val shouldReset = when (profile.quotaConfig.resetPeriod) {
            com.example.ai.model.QuotaResetPeriod.DAILY -> elapsed >= (24 * 60 * 60 * 1000L)
            com.example.ai.model.QuotaResetPeriod.MONTHLY -> elapsed >= (30L * 24 * 60 * 60 * 1000L)
            com.example.ai.model.QuotaResetPeriod.TOTAL -> false
        }

        if (shouldReset) {
            prefs.edit()
                .putLong("${pKey}_period_start", currentTimeMs)
                .putLong("${pKey}_period_requests", 0L)
                .putLong("${pKey}_period_tokens", 0L)
                .putLong("${pKey}_period_audio_ms", 0L)
                .putInt("${pKey}_period_quota_blocked", 0)
                .putInt("${pKey}_period_failure", 0)
                .apply()
        }
    }

    fun getUsage(task: AiTask, provider: AiProviderType): AiUsageRecord {
        val key = buildKey(task, provider)
        val successes = prefs.getInt("${key}_success", 0)
        val failures = prefs.getInt("${key}_failure", 0)
        val quotaBlocks = prefs.getInt("${key}_quota", 0)
        val promptTokens = prefs.getLong("${key}_prompt_tokens", 0L)
        val completionTokens = prefs.getLong("${key}_completion_tokens", 0L)
        val totalTokens = prefs.getLong("${key}_total_tokens", 0L)

        return AiUsageRecord(
            task = task,
            provider = provider,
            successCount = successes,
            failureCount = failures,
            quotaBlockedCount = quotaBlocks,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
    }

    fun getAllUsage(): List<AiUsageRecord> {
        val list = mutableListOf<AiUsageRecord>()
        for (task in AiTask.values()) {
            for (provider in AiProviderType.values()) {
                val record = getUsage(task, provider)
                if (record.totalRequests > 0 || record.totalTokens > 0) {
                    list.add(record)
                }
            }
        }
        return list
    }

    @Synchronized
    fun resetAll() {
        prefs.edit().clear().apply()
        _usageFlow.value = loadAllUsage()
    }

    @Synchronized
    fun resetProfileUsage(profileId: String) {
        val pKey = "profile_$profileId"
        prefs.edit()
            .remove("${pKey}_success")
            .remove("${pKey}_failure")
            .remove("${pKey}_quota")
            .remove("${pKey}_tokens")
            .remove("${pKey}_audio_ms")
            .remove("${pKey}_period_requests")
            .remove("${pKey}_period_tokens")
            .remove("${pKey}_period_audio_ms")
            .remove("${pKey}_period_quota_blocked")
            .remove("${pKey}_period_failure")
            .remove("${pKey}_period_start")
            .apply()
    }

    private fun saveUsage(record: AiUsageRecord) {
        val key = buildKey(record.task, record.provider)
        prefs.edit()
            .putInt("${key}_success", record.successCount)
            .putInt("${key}_failure", record.failureCount)
            .putInt("${key}_quota", record.quotaBlockedCount)
            .putLong("${key}_prompt_tokens", record.promptTokens)
            .putLong("${key}_completion_tokens", record.completionTokens)
            .putLong("${key}_total_tokens", record.totalTokens)
            .apply()

        val map = _usageFlow.value.toMutableMap()
        map[key] = record
        _usageFlow.value = map
    }

    private fun loadAllUsage(): Map<String, AiUsageRecord> {
        val map = mutableMapOf<String, AiUsageRecord>()
        for (task in AiTask.values()) {
            for (provider in AiProviderType.values()) {
                val key = buildKey(task, provider)
                map[key] = getUsage(task, provider)
            }
        }
        return map
    }

    private fun buildKey(task: AiTask, provider: AiProviderType): String {
        return "${task.id}_${provider.id}"
    }
}
