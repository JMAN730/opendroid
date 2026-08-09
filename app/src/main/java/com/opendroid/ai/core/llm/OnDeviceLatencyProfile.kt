package com.opendroid.ai.core.llm

import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import javax.inject.Inject
import javax.inject.Singleton

enum class LatencyBudgetStatus {
    UNPROFILED,
    WITHIN_BUDGET,
    EXCEEDED
}

data class LatencyBudgetResult(
    val status: LatencyBudgetStatus,
    val measuredLatencyMs: Long,
    val acceptedBudgetMs: Long? = null,
    val message: String? = null
)

/** Measurements collected on one device for one model tier. */
@Serializable
data class OnDeviceLatencyProfile(
    val samplesMs: List<Long> = emptyList(),
    val updatedAtMillis: Long = 0L
) {
    fun acceptedBudgetMs(minSamples: Int = MIN_SAMPLES): Long? =
        samplesMs.takeIf { it.size >= minSamples }?.let(::p95)

    fun record(latencyMs: Long, nowMillis: Long): OnDeviceLatencyProfile = copy(
        samplesMs = (samplesMs + latencyMs.coerceAtLeast(0L)).takeLast(MAX_SAMPLES),
        updatedAtMillis = nowMillis
    )

    private fun p95(values: List<Long>): Long {
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    companion object {
        const val MIN_SAMPLES = 3
        const val MAX_SAMPLES = 20
    }
}

object OnDeviceLatencyBudget {
    fun classify(profile: OnDeviceLatencyProfile?, latencyMs: Long): LatencyBudgetResult {
        val budget = profile?.acceptedBudgetMs()
        val measured = latencyMs.coerceAtLeast(0L)
        return when {
            budget == null -> LatencyBudgetResult(
                status = LatencyBudgetStatus.UNPROFILED,
                measuredLatencyMs = measured
            )
            measured <= budget -> LatencyBudgetResult(
                status = LatencyBudgetStatus.WITHIN_BUDGET,
                measuredLatencyMs = measured,
                acceptedBudgetMs = budget
            )
            else -> LatencyBudgetResult(
                status = LatencyBudgetStatus.EXCEEDED,
                measuredLatencyMs = measured,
                acceptedBudgetMs = budget,
                message = "On-device planning took ${measured} ms, above this device's measured ${budget} ms budget."
            )
        }
    }

    fun modelTierKey(modelId: String): String {
        val spec = OnDeviceModelRegistry.findById(modelId)
        return if (spec == null) {
            "UNKNOWN:$modelId"
        } else {
            "${spec.backend.name}:${spec.sizeLabel}"
        }
    }

    fun profileKey(deviceKey: String, modelId: String): String =
        "$deviceKey|${modelTierKey(modelId)}"
}

/**
 * Persists local planning measurements without using hardware identifiers that
 * identify a particular installation. Profiles are keyed by manufacturer,
 * model, SDK, backend, and model tier.
 */
@Singleton
class OnDeviceLatencyTracker @Inject constructor(
    private val settingsRepository: com.opendroid.ai.data.repository.SettingsRepository
) {
    suspend fun recordPlanning(response: LLMResponse): LatencyBudgetResult? {
        if (!ProviderCatalog.isOnDevice(response.provider) &&
            OnDeviceModelRegistry.findById(response.model)?.backend == null
        ) return null

        val key = OnDeviceLatencyBudget.profileKey(currentDeviceKey(), response.model)
        val current = settingsRepository.llmConfig.first().onDeviceLatencyProfiles[key]
        val result = OnDeviceLatencyBudget.classify(current, response.latencyMs)
        val updated = (current ?: OnDeviceLatencyProfile()).record(
            latencyMs = response.latencyMs,
            nowMillis = System.currentTimeMillis()
        )
        settingsRepository.updateConfig { config ->
            config.copy(onDeviceLatencyProfiles = config.onDeviceLatencyProfiles + (key to updated))
        }
        return result
    }

    companion object {
        fun currentDeviceKey(): String = listOf(
            Build.MANUFACTURER,
            Build.MODEL,
            "sdk-${Build.VERSION.SDK_INT}"
        ).joinToString(":") { it.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_") }
    }
}
