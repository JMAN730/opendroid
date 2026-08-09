package com.opendroid.ai.core.llm

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/** A measured profile for one model tier on one concrete device. */
@Serializable
data class LatencyProfile(
    val provider: String,
    val model: String,
    val modelTier: String,
    val referenceHardware: String,
    val samplesMs: List<Long> = emptyList(),
    val measuredAtMillis: Long = 0L
) {
    val sampleCount: Int get() = samplesMs.size
    val p50Ms: Long? get() = percentile(0.50)
    val p95Ms: Long? get() = percentile(0.95)

    fun classify(latencyMs: Long): LatencyClassification =
        p95Ms?.let { budget ->
            if (latencyMs <= budget) LatencyClassification.WITHIN_MEASURED_PROFILE
            else LatencyClassification.EXCEEDS_MEASURED_PROFILE
        } ?: LatencyClassification.UNMEASURED

    private fun percentile(fraction: Double): Long? {
        if (samplesMs.isEmpty()) return null
        val sorted = samplesMs.sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
enum class LatencyClassification {
    UNMEASURED,
    WITHIN_MEASURED_PROFILE,
    EXCEEDS_MEASURED_PROFILE
}

data class LatencyObservation(
    val profile: LatencyProfile,
    val classification: LatencyClassification
)

object LatencyProfileRecorder {
    private const val MAX_SAMPLES = 32

    fun key(provider: String, model: String, referenceHardware: String): String =
        listOf(provider.trim(), model.trim(), referenceHardware.trim())
            .joinToString("|") { it.replace("|", "_") }

    fun observe(
        previous: LatencyProfile?,
        provider: String,
        model: String,
        modelTier: String,
        referenceHardware: String,
        latencyMs: Long,
        measuredAtMillis: Long
    ): LatencyObservation {
        require(latencyMs >= 0L) { "Latency cannot be negative." }
        val classification = previous?.classify(latencyMs) ?: LatencyClassification.UNMEASURED
        val samples = (previous?.samplesMs.orEmpty() + latencyMs).takeLast(MAX_SAMPLES)
        return LatencyObservation(
            profile = LatencyProfile(
                provider = provider,
                model = model,
                modelTier = modelTier,
                referenceHardware = referenceHardware,
                samplesMs = samples,
                measuredAtMillis = measuredAtMillis
            ),
            classification = classification
        )
    }
}
