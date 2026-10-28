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
