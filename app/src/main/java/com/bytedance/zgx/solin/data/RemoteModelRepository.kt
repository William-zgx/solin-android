package com.bytedance.zgx.solin.data

import android.content.Context
import android.os.SystemClock
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.RemoteConnectivitySnapshot
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.hasSameConnectivityTarget
import com.bytedance.zgx.solin.logging.solinW
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_REMOTE
import java.security.MessageDigest
import java.util.UUID

private const val LEGACY_PREFS_NAME = "solin"
private const val PREF_REMOTE_API_KEY = "remote_model_api_key"
private const val PREF_REMOTE_CONFIG_INTEGRITY_SHA256 = "remote_model_config_integrity_sha256"

class RemoteModelRepository(
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    context: Context? = null,
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val revisionFactory: () -> String = { UUID.randomUUID().toString() },
) : RemoteModelStore {
    constructor(context: Context) : this(
        PreferenceSettingsStore(context),
        EncryptedSecretStore(context),
        context,
    )

    private val legacyPrefs = context?.applicationContext?.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val configLock = Any()
    private var connectivitySnapshot: RemoteConnectivitySnapshot? = null
    private var configStateValid: Boolean = true

    override fun loadMode(): InferenceMode =
        settingsStore.loadInferenceMode()

    override fun saveMode(mode: InferenceMode): InferenceMode =
        settingsStore.saveInferenceMode(mode)

    override fun loadConfig(): RemoteModelConfig = synchronized(configLock) {
        if (!configStateValid) return@synchronized RemoteModelConfig()
        runCatching {
            val persisted = loadPersistedConfigLocked()
            persisted.copy(
                connectivityStatus = currentConnectivityLocked(persisted)?.status
                    ?: RemoteModelConnectivityStatus.Unknown,
            )
        }.getOrElse {
            connectivitySnapshot = null
            RemoteModelConfig()
        }
    }

    override fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig> =
        synchronized(configLock) {
            runCatching {
                check(configStateValid) { "Remote config state is unavailable" }
                val current = loadPersistedConfigLocked()
                val requested = config.normalized()
                val revisionChanged = current.profileRevision.isBlank() ||
                    !requested.hasSameConnectivityTarget(current)
                val revision = resolveRevisionLocked(current, revisionChanged)
                val prepared = requested.copy(
                    profileRevision = revision,
                    connectivityStatus = RemoteModelConnectivityStatus.Unknown,
                ).normalized()
                val previousIntegrity = try {
                    secretStore.loadString(PREF_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow()
                } catch (readFailure: Throwable) {
                    connectivitySnapshot = null
                    throw readFailure
                }
                val integrity = prepared.remoteConfigIntegritySha256()
                val apiKeySave = secretStore.saveString(PREF_REMOTE_API_KEY, prepared.apiKey)
                val apiKeyFailure = apiKeySave.exceptionOrNull()
                if (apiKeyFailure != null) {
                    val readback = secretStore.loadString(PREF_REMOTE_API_KEY)
                    if (readback.isFailure || readback.getOrNull() != current.apiKey) {
                        connectivitySnapshot = null
                        readback.exceptionOrNull()?.let(apiKeyFailure::addSuppressed)
                        val rollback = secretStore.saveString(PREF_REMOTE_API_KEY, current.apiKey)
                        if (rollback.isFailure) configStateValid = false
                        rollback.exceptionOrNull()?.let(apiKeyFailure::addSuppressed)
                    }
                    throw apiKeyFailure
                }
                val saved = try {
                    secretStore.saveString(PREF_REMOTE_CONFIG_INTEGRITY_SHA256, integrity).getOrThrow()
                    saveNonSecretConfigLocked(prepared)
                    val storedApiKey = secretStore.loadString(PREF_REMOTE_API_KEY).getOrThrow()
                    check(storedApiKey == prepared.apiKey) { "Remote API key verification failed" }
                    loadPersistedConfigWithApiKeyLocked(storedApiKey)
                } catch (writeFailure: Throwable) {
                    val apiKeyRollback = runCatching {
                        secretStore.saveString(PREF_REMOTE_API_KEY, current.apiKey).getOrThrow()
                    }
                    val integrityRollback = runCatching {
                        secretStore.saveString(PREF_REMOTE_CONFIG_INTEGRITY_SHA256, previousIntegrity).getOrThrow()
                    }
                    connectivitySnapshot = null
                    if (apiKeyRollback.isFailure || integrityRollback.isFailure) {
                        configStateValid = false
                    }
                    apiKeyRollback.exceptionOrNull()?.let(writeFailure::addSuppressed)
                    integrityRollback.exceptionOrNull()?.let(writeFailure::addSuppressed)
                    throw writeFailure
                }
                if (revisionChanged) connectivitySnapshot = null
                legacyPrefs?.edit()?.remove(PREF_REMOTE_API_KEY)?.apply()
                projectConnectivityLocked(saved)
            }
        }

    override fun saveConfigWithoutApiKey(config: RemoteModelConfig): Result<RemoteModelConfig> =
        synchronized(configLock) {
            runCatching {
                check(configStateValid) { "Remote config state is unavailable" }
                val current = loadPersistedConfigLocked(migrateLegacySecret = false)
                val requested = config.copy(apiKey = current.apiKey).normalized()
                if (current.apiKey.isNotEmpty() && !requested.hasSameConnectivityTarget(current)) {
                    error("Remote target cannot change without an explicit API key")
                }
                val revisionChanged = current.profileRevision.isBlank() ||
                    !requested.hasSameConnectivityTarget(current)
                val revision = resolveRevisionLocked(current, revisionChanged)
                val prepared = requested.copy(
                    profileRevision = revision,
                    connectivityStatus = RemoteModelConnectivityStatus.Unknown,
                ).normalized()
                val previousIntegrity = try {
                    secretStore.loadString(PREF_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow()
                } catch (readFailure: Throwable) {
                    connectivitySnapshot = null
                    throw readFailure
                }
                val integrity = prepared.remoteConfigIntegritySha256()
                val saved = try {
                    secretStore.saveString(PREF_REMOTE_CONFIG_INTEGRITY_SHA256, integrity).getOrThrow()
                    saveNonSecretConfigLocked(prepared)
                    loadPersistedConfigWithApiKeyLocked(current.apiKey)
                } catch (writeFailure: Throwable) {
                    val integrityRollback = runCatching {
                        secretStore.saveString(PREF_REMOTE_CONFIG_INTEGRITY_SHA256, previousIntegrity).getOrThrow()
                    }
                    connectivitySnapshot = null
                    if (integrityRollback.isFailure) configStateValid = false
                    integrityRollback.exceptionOrNull()?.let(writeFailure::addSuppressed)
                    throw writeFailure
                }
                if (revisionChanged) connectivitySnapshot = null
                projectConnectivityLocked(saved)
            }
        }

    override fun recordConnectivity(
        config: RemoteModelConfig,
        status: RemoteModelConnectivityStatus,
    ) = synchronized(configLock) {
        if (!configStateValid) return@synchronized
        val revision = config.profileRevision.trim()
        if (revision.isBlank()) return@synchronized
        val persistedRevision = runCatching {
            loadPersistedConfigLocked(migrateLegacySecret = false).profileRevision.trim()
        }.getOrElse {
            connectivitySnapshot = null
            return@synchronized
        }
        if (persistedRevision.isNullOrBlank() || persistedRevision != revision) return@synchronized
        connectivitySnapshot = RemoteConnectivitySnapshot(
            configRevision = revision,
            status = status,
            checkedAtElapsedRealtimeMs = elapsedRealtimeMillis(),
        )
    }

    override fun currentConnectivity(config: RemoteModelConfig): RemoteConnectivitySnapshot? =
        synchronized(configLock) {
            currentConnectivityLocked(config)
        }

    private fun currentConnectivityLocked(config: RemoteModelConfig): RemoteConnectivitySnapshot? {
        if (!configStateValid) return null
        val revision = config.profileRevision.trim()
        val snapshot = connectivitySnapshot ?: return null
        if (revision.isBlank() || snapshot.configRevision != revision) return null
        if (!snapshot.isFresh(elapsedRealtimeMillis())) {
            if (connectivitySnapshot === snapshot) connectivitySnapshot = null
            return null
        }
        return snapshot
    }

    override fun invalidateConnectivity() = synchronized(configLock) {
        connectivitySnapshot = null
    }

    private fun loadPersistedConfigLocked(migrateLegacySecret: Boolean = true): RemoteModelConfig =
        loadPersistedConfigWithApiKeyLocked(loadApiKey(migrateLegacySecret).getOrThrow())

    private fun loadPersistedConfigWithApiKeyLocked(apiKey: String): RemoteModelConfig {
        val persisted = settingsStore.loadRemoteConfig(apiKey)
            .normalized()
            .copy(connectivityStatus = RemoteModelConnectivityStatus.Unknown)
        if (persisted.profileRevision.isBlank()) return persisted
        val integrity = secretStore.loadString(PREF_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow()
        require(
            integrity.isNotBlank() && integrity == persisted.remoteConfigIntegritySha256(),
        ) { "Remote config integrity check failed" }
        return persisted
    }

    private fun resolveRevisionLocked(
        current: RemoteModelConfig,
        revisionChanged: Boolean,
    ): String =
        if (revisionChanged) {
            revisionFactory().trim().also { generated ->
                require(generated.isNotBlank()) { "Remote profile revision must not be blank" }
            }
        } else {
            current.profileRevision
        }

    private fun saveNonSecretConfigLocked(
        prepared: RemoteModelConfig,
    ) {
        settingsStore.saveRemoteConfig(prepared)
    }

    private fun projectConnectivityLocked(config: RemoteModelConfig): RemoteModelConfig =
        config.copy(
            connectivityStatus = currentConnectivityLocked(config)?.status
                ?: RemoteModelConnectivityStatus.Unknown,
        )

    private fun loadApiKey(migrateLegacySecret: Boolean = true): Result<String> {
        val plaintextLegacy = legacyPrefs?.getString(PREF_REMOTE_API_KEY, "").orEmpty()
        if (plaintextLegacy.isNotBlank()) {
            // Migration strategy: attempt to move the legacy plaintext key into encrypted
            // storage. If the encrypted write fails, we log a warning and still return the
            // plaintext key so the app keeps working. The migration will be retried on the
            // next loadApiKey() call (e.g. next app launch), since the plaintext copy is
            // only removed after a successful save.
            if (migrateLegacySecret) {
                val saved = secretStore.saveString(PREF_REMOTE_API_KEY, plaintextLegacy)
                if (saved.isSuccess) {
                    legacyPrefs?.edit()?.remove(PREF_REMOTE_API_KEY)?.apply()
                } else {
                    solinW(
                        TAG_REMOTE,
                        "Failed to migrate plaintext API key to encrypted storage; " +
                            "retaining plaintext copy and retrying on next load. " +
                            "Cause: ${saved.exceptionOrNull()?.message}",
                    )
                }
            }
            return Result.success(plaintextLegacy)
        }
        return secretStore.loadString(PREF_REMOTE_API_KEY)
    }
}

private fun RemoteModelConfig.remoteConfigIntegritySha256(): String {
    val normalized = normalized()
    val encoded = buildString {
        appendLengthPrefixed(normalized.baseUrl)
        appendLengthPrefixed(normalized.modelName)
        appendLengthPrefixed(normalized.apiKey)
        appendLengthPrefixed(normalized.supportsVisionInput.toString())
        appendLengthPrefixed(normalized.supportsToolCalls.toString())
        appendLengthPrefixed(normalized.contextWindowTokens?.toString().orEmpty())
        appendLengthPrefixed(normalized.profileRevision)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(encoded.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length)
    append(':')
    append(value)
    append(';')
}
