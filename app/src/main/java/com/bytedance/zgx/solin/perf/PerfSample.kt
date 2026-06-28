package com.bytedance.zgx.solin.perf

enum class PerfSampleValueKind {
    DurationMs,
    Throughput,
}

object PerfSampleSource {
    const val LOCAL_JVM = "localJvm"
    const val EMULATOR = "emulator"
    const val PHYSICAL_ARM64 = "physicalArm64"

    val allowed: Set<String> = setOf(LOCAL_JVM, EMULATOR, PHYSICAL_ARM64)
}

enum class PerfSampleKey(
    val id: String,
    val valueKind: PerfSampleValueKind,
    val requiresPhysicalArm64: Boolean = false,
) {
    ModelLoadMs("modelLoadMs", PerfSampleValueKind.DurationMs, requiresPhysicalArm64 = true),
    FirstTokenMs("firstTokenMs", PerfSampleValueKind.DurationMs, requiresPhysicalArm64 = true),
    TokensPerSecond("tokensPerSecond", PerfSampleValueKind.Throughput, requiresPhysicalArm64 = true),
    ZvecStorageOpenMs("zvecStorageOpenMs", PerfSampleValueKind.DurationMs, requiresPhysicalArm64 = true),
    ZvecMemorySearch50kMs("zvecMemorySearch50kMs", PerfSampleValueKind.DurationMs, requiresPhysicalArm64 = true),
    ZvecMemoryIndex50kMs("zvecMemoryIndex50kMs", PerfSampleValueKind.DurationMs, requiresPhysicalArm64 = true),
    RealAppSearchMs("realAppSearchMs", PerfSampleValueKind.DurationMs, requiresPhysicalArm64 = true),
    OcrMs("ocrMs", PerfSampleValueKind.DurationMs);
}

data class PerfSample(
    val key: PerfSampleKey,
    val source: String,
    val durationMs: Long? = null,
    val throughput: Double? = null,
)

data class PerfSampleValidationResult(
    val sample: PerfSample,
    val errors: List<String>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

fun PerfSample.validate(): PerfSampleValidationResult {
    val errors = buildList {
        if (source !in PerfSampleSource.allowed) {
            add("source must be one of localJvm, emulator, physicalArm64")
        }
        when (key.valueKind) {
            PerfSampleValueKind.DurationMs -> {
                if (durationMs == null) {
                    add("durationMs is required for ${key.id}")
                }
                if (throughput != null) {
                    add("throughput must be absent for ${key.id}")
                }
            }

            PerfSampleValueKind.Throughput -> {
                if (throughput == null) {
                    add("throughput is required for ${key.id}")
                }
                if (durationMs != null) {
                    add("durationMs must be absent for ${key.id}")
                }
            }
        }
        if (durationMs != null && durationMs < 0) {
            add("durationMs must be non-negative")
        }
        if (throughput != null && (!throughput.isFinite() || throughput <= 0.0)) {
            add("throughput must be positive")
        }
        if (key.requiresPhysicalArm64 && source != PerfSampleSource.PHYSICAL_ARM64) {
            add("source must be physicalArm64 for ${key.id}")
        }
    }
    return PerfSampleValidationResult(sample = this, errors = errors)
}
