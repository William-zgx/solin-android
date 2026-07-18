package com.bytedance.zgx.solin.data

import android.content.Context
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.isUsable
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val LEGACY_PREFS_NAME = "solin"
private const val CURRENT_MIGRATION_VERSION = 2
private const val LEGACY_SESSIONS_KEY = "sessions_json"
private const val LEGACY_DOWNLOAD_ID_KEY = "download_id"
private const val LEGACY_DOWNLOAD_SOURCE_KEY = "download_source"
private const val LEGACY_REMOTE_API_KEY = "remote_model_api_key"
private const val LEGACY_SENSITIVE_CLEANUP_VERSION_KEY = "legacy_sensitive_cleanup_version"

class LegacyPrefsMigrator(
    private val context: Context,
    private val database: SolinDatabase,
    private val settingsStore: PreferenceSettingsStore,
    private val secretStore: SecretStore,
) {
    fun migrateIfNeeded(): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val migrationVersion = settingsStore.migrationVersion()
        if (migrationVersion >= CURRENT_MIGRATION_VERSION) return true
        if (prefs.getInt(LEGACY_SENSITIVE_CLEANUP_VERSION_KEY, 0) >= CURRENT_MIGRATION_VERSION) {
            return runCatching {
                settingsStore.setMigrationVersion(CURRENT_MIGRATION_VERSION)
            }.isSuccess
        }
        if (migrationVersion == CURRENT_MIGRATION_VERSION - 1) {
            val migratedSensitiveKeys = previouslyMigratedSensitiveKeys(prefs) ?: return false
            if (!clearMigratedSensitiveKeys(prefs, migratedSensitiveKeys)) return false
            return runCatching {
                settingsStore.setMigrationVersion(CURRENT_MIGRATION_VERSION)
            }.isSuccess
        }

        val migratedSensitiveKeys = linkedSetOf<String>()
        try {
            migratedSensitiveKeys += migrateSessions(
                encoded = prefs.getString(LEGACY_SESSIONS_KEY, null),
                activeSessionId = prefs.getString("active_session_id", null),
            )
            migrateModels(
                installedModelsJson = prefs.getString("installed_models_json", null),
                legacyModelPath = prefs.getString("model_path", null),
                activeInstalledModelId = prefs.getString("active_installed_model_id", null),
                selectedModelId = prefs.getString("selected_model_id", null),
            )
            migratedSensitiveKeys += migrateDownload(
                downloadId = prefs.getLong(LEGACY_DOWNLOAD_ID_KEY, -1L),
                sourceJson = prefs.getString(LEGACY_DOWNLOAD_SOURCE_KEY, null),
            )
            migrateSettings(prefs)
            migratedSensitiveKeys += migrateRemote(prefs)
        } catch (_: Exception) {
            return false
        }

        if (!clearMigratedSensitiveKeys(prefs, migratedSensitiveKeys)) return false
        return runCatching {
            settingsStore.setMigrationVersion(CURRENT_MIGRATION_VERSION)
        }.isSuccess
    }

    private fun migrateSessions(encoded: String?, activeSessionId: String?): Set<String> {
        if (encoded == null) return emptySet()
        val sessions = decodeSessions(encoded)
            ?: throw IllegalArgumentException("无法解析旧会话数据")
        if (sessions.isEmpty()) return setOf(LEGACY_SESSIONS_KEY)

        if (!legacySessionsArePersisted(sessions)) {
            check(database.sessionDao().sessions().isEmpty()) {
                "旧会话迁移与现有会话冲突"
            }
            database.runInTransaction {
                sessions.forEach { legacy ->
                    database.sessionDao().upsertSession(
                        ChatSessionEntity(
                            id = legacy.id,
                            title = legacy.title,
                            createdAtMillis = legacy.createdAtMillis,
                            updatedAtMillis = legacy.updatedAtMillis,
                        ),
                    )
                    database.sessionDao().insertMessages(
                        legacy.messages.mapIndexed { index, message ->
                            ChatMessageEntity(
                                sessionId = legacy.id,
                                position = index,
                                role = message.role,
                                text = message.text,
                                tokenCount = message.tokenCount,
                                tokensPerSecond = message.tokensPerSecond,
                                privacy = MessagePrivacy.LocalOnly.name,
                            )
                        },
                    )
                }
            }
        }

        val active = sessions.firstOrNull { it.id == activeSessionId }?.id ?: sessions.first().id
        settingsStore.saveActiveSessionId(active)
        return setOf(LEGACY_SESSIONS_KEY)
    }

    private fun migrateModels(
        installedModelsJson: String?,
        legacyModelPath: String?,
        activeInstalledModelId: String?,
        selectedModelId: String?,
    ) {
        if (selectedModelId?.isNotBlank() == true) {
            settingsStore.saveSelectedModelId(selectedModelId)
        } else {
            settingsStore.saveSelectedModelId(DEFAULT_CHAT_MODEL_ID)
        }
        if (activeInstalledModelId?.isNotBlank() == true) {
            settingsStore.saveActiveInstalledModelId(activeInstalledModelId)
        }

        if (database.modelDao().models().isNotEmpty()) return
        installedModelsJson?.let { encoded ->
            val array = JSONArray(encoded)
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                val path = json.optString("path").takeIf { it.isNotBlank() } ?: continue
                val file = File(path)
                if (!file.exists()) continue
                val recommendedId = json.optStringOrNull("recommendedModelId")
                insertLegacyModel(
                    id = json.optString("id").takeIf { it.isNotBlank() }
                        ?: recommendedId
                        ?: "local-${Integer.toHexString(path.hashCode())}",
                    displayName = json.optString("displayName").takeIf { it.isNotBlank() }
                        ?: recommendedId?.let { ModelCatalog.recommendedModelOrNull(it)?.shortName }
                        ?: file.nameWithoutExtension,
                    path = path,
                    recommendedModelId = recommendedId,
                )
            }
        }

        legacyModelPath?.takeIf { File(it).exists() }?.let { path ->
            val file = File(path)
            insertLegacyModel(
                id = "local-${Integer.toHexString(path.hashCode())}",
                displayName = file.nameWithoutExtension,
                path = path,
                recommendedModelId = null,
            )
        }
    }

    private fun insertLegacyModel(
        id: String,
        displayName: String,
        path: String,
        recommendedModelId: String?,
    ) {
        val file = File(path)
        val model = recommendedModelId?.let { ModelCatalog.recommendedModelOrNull(it) }
        database.modelDao().upsert(
            InstalledModelEntity(
                id = id,
                displayName = displayName,
                path = path,
                fileBytes = file.length().coerceAtLeast(0L),
                recommendedModelId = recommendedModelId,
                sourceRevision = model?.sourceRevision,
                verifiedSha256 = null,
                verificationStatus = if (recommendedModelId == null) {
                    ModelVerificationStatus.UnverifiedCustom.name
                } else {
                    ModelVerificationStatus.LegacyUnverified.name
                },
            ),
        )
    }

    private fun migrateDownload(downloadId: Long, sourceJson: String?): Set<String> {
        if (downloadId <= 0L && sourceJson.isNullOrBlank()) return emptySet()
        require(downloadId > 0L && !sourceJson.isNullOrBlank()) {
            "旧下载状态不完整"
        }
        val sanitizedSourceJson = requireNotNull(sanitizePersistedDownloadSourceJson(sourceJson)) {
            "无法解析旧下载来源"
        }
        val existing = database.downloadRecordDao().record()
        if (existing == null) {
            database.downloadRecordDao().upsert(
                DownloadRecordEntity(
                    id = PENDING_DOWNLOAD_RECORD_ID,
                    downloadManagerId = downloadId,
                    sourceJson = sanitizedSourceJson,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        } else {
            check(
                existing.downloadManagerId == downloadId &&
                    existing.sourceJson == sanitizedSourceJson,
            ) {
                "旧下载状态与现有记录冲突"
            }
        }
        return setOf(LEGACY_DOWNLOAD_ID_KEY, LEGACY_DOWNLOAD_SOURCE_KEY)
    }

    private fun migrateSettings(prefs: android.content.SharedPreferences) {
        settingsStore.setMemoryEnabled(prefs.getBoolean("memory_enabled", true))
        if (prefs.getBoolean("first_run_setup_dismissed", false)) settingsStore.markSetupDismissed()
        settingsStore.saveGenerationParameters(
            GenerationParameters(
                temperature = prefs.getFloat("generation_temperature", GenerationParameters.DEFAULT_TEMPERATURE),
                topP = prefs.getFloat("generation_top_p", GenerationParameters.DEFAULT_TOP_P),
                topK = prefs.getInt("generation_top_k", GenerationParameters.DEFAULT_TOP_K),
            ),
        )
        settingsStore.saveInferenceMode(
            decodeInferenceMode(prefs.getString("inference_mode", null)),
        )
        runCatching {
            settingsStore.saveBackend(
                com.bytedance.zgx.solin.BackendChoice.valueOf(prefs.getString("backend", "").orEmpty()),
            )
        }
    }

    private fun migrateRemote(prefs: android.content.SharedPreferences): Set<String> {
        settingsStore.saveRemoteConfig(
            com.bytedance.zgx.solin.RemoteModelConfig(
                baseUrl = prefs.getString("remote_model_base_url", "").orEmpty(),
                modelName = prefs.getString("remote_model_name", "").orEmpty(),
            ),
        )
        val legacyKey = prefs.getString("remote_model_api_key", "").orEmpty()
        if (legacyKey.isBlank()) return emptySet()
        secretStore.saveString(LEGACY_REMOTE_API_KEY, legacyKey).getOrThrow()
        return setOf(LEGACY_REMOTE_API_KEY)
    }

    private fun clearMigratedSensitiveKeys(
        prefs: android.content.SharedPreferences,
        migratedKeys: Set<String>,
    ): Boolean {
        if (migratedKeys.isEmpty()) return true
        return prefs.edit().apply {
            migratedKeys.forEach { key -> remove(key) }
            putInt(LEGACY_SENSITIVE_CLEANUP_VERSION_KEY, CURRENT_MIGRATION_VERSION)
        }.commit()
    }

    private fun previouslyMigratedSensitiveKeys(
        prefs: android.content.SharedPreferences,
    ): Set<String>? {
        val migratedKeys = linkedSetOf<String>()
        prefs.getString(LEGACY_SESSIONS_KEY, null)?.let { encoded ->
            val sessions = decodeSessions(encoded) ?: return null
            if (sessions.isNotEmpty() && !legacySessionsArePersisted(sessions)) return null
            migratedKeys += LEGACY_SESSIONS_KEY
        }

        val hasLegacyDownloadId = prefs.contains(LEGACY_DOWNLOAD_ID_KEY)
        val hasLegacyDownloadSource = prefs.contains(LEGACY_DOWNLOAD_SOURCE_KEY)
        if (hasLegacyDownloadId != hasLegacyDownloadSource) return null
        if (hasLegacyDownloadId) {
            val downloadId = prefs.getLong(LEGACY_DOWNLOAD_ID_KEY, -1L)
            val sourceJson = prefs.getString(LEGACY_DOWNLOAD_SOURCE_KEY, null)
            val sanitizedSourceJson = sourceJson?.let(::sanitizePersistedDownloadSourceJson) ?: return null
            val persistedDownload = database.downloadRecordDao().record()
            if (
                downloadId <= 0L ||
                    persistedDownload?.downloadManagerId != downloadId ||
                    persistedDownload.sourceJson != sanitizedSourceJson
            ) {
                return null
            }
            migratedKeys += LEGACY_DOWNLOAD_ID_KEY
            migratedKeys += LEGACY_DOWNLOAD_SOURCE_KEY
        }

        if (prefs.contains(LEGACY_REMOTE_API_KEY)) {
            val legacyKey = prefs.getString(LEGACY_REMOTE_API_KEY, "").orEmpty()
            if (
                legacyKey.isNotBlank() &&
                    secretStore.loadString(LEGACY_REMOTE_API_KEY).getOrNull() != legacyKey
            ) {
                return null
            }
            migratedKeys += LEGACY_REMOTE_API_KEY
        }
        return migratedKeys
    }

    private fun legacySessionsArePersisted(sessions: List<LegacySession>): Boolean {
        val sessionDao = database.sessionDao()
        return sessions.all { legacy ->
            val persistedSession = sessionDao.session(legacy.id) ?: return@all false
            if (
                persistedSession.title != legacy.title ||
                    persistedSession.createdAtMillis != legacy.createdAtMillis ||
                    persistedSession.updatedAtMillis != legacy.updatedAtMillis
            ) {
                return@all false
            }
            val persistedMessages = sessionDao.messagesForSession(legacy.id)
            persistedMessages.size == legacy.messages.size &&
                persistedMessages.zip(legacy.messages).all { (persisted, source) ->
                    persisted.role == source.role &&
                        persisted.text == source.text &&
                        persisted.tokenCount == source.tokenCount &&
                        persisted.tokensPerSecond == source.tokensPerSecond &&
                        persisted.privacy == MessagePrivacy.LocalOnly.name
                }
        }
    }

    private fun decodeSessions(encoded: String): List<LegacySession>? =
        runCatching {
            val array = if (encoded.trimStart().startsWith("[")) {
                JSONArray(encoded)
            } else {
                JSONObject(encoded).optJSONArray("sessions") ?: JSONArray()
            }
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    val messages = json.optJSONArray("messages").orEmptyMessages()
                    add(
                        LegacySession(
                            id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                            title = json.optString("title").takeIf { it.isNotBlank() }
                                ?: SessionTitleRules.deriveTitle(messages.map { it.toChatMessage() }),
                            createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
                            updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
                            messages = messages,
                        ),
                    )
                }
            }
        }.getOrNull()

    private fun JSONArray?.orEmptyMessages(): List<LegacyMessage> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val text = json.optString("text")
                if (text.isBlank()) continue
                val stats = json.optJSONObject("generationStats")
                add(
                    LegacyMessage(
                        role = json.optString("role").takeIf { it.isNotBlank() } ?: MessageRole.Assistant.name,
                        text = text,
                        tokenCount = stats?.optInt("tokenCount")?.takeIf { it > 0 },
                        tokensPerSecond = stats?.optDouble("tokensPerSecond")?.takeIf { value ->
                            com.bytedance.zgx.solin.GenerationStats(1, value).isUsable()
                        },
                    ),
                )
            }
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

    private data class LegacySession(
        val id: String,
        val title: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
        val messages: List<LegacyMessage>,
    )

    private data class LegacyMessage(
        val role: String,
        val text: String,
        val tokenCount: Int?,
        val tokensPerSecond: Double?,
    ) {
        fun toChatMessage(): com.bytedance.zgx.solin.ChatMessage =
            com.bytedance.zgx.solin.ChatMessage(
                role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.Assistant),
                text = text,
                privacy = MessagePrivacy.LocalOnly,
            )
    }
}
