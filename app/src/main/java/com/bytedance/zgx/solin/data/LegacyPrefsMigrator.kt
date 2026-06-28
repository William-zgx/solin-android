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
private const val CURRENT_MIGRATION_VERSION = 1

class LegacyPrefsMigrator(
    private val context: Context,
    private val database: SolinDatabase,
    private val settingsStore: PreferenceSettingsStore,
    private val secretStore: SecretStore,
) {
    fun migrateIfNeeded() {
        if (settingsStore.migrationVersion() >= CURRENT_MIGRATION_VERSION) return
        val prefs = context.applicationContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        migrateSessions(prefs.getString("sessions_json", null), prefs.getString("active_session_id", null))
        migrateModels(
            installedModelsJson = prefs.getString("installed_models_json", null),
            legacyModelPath = prefs.getString("model_path", null),
            activeInstalledModelId = prefs.getString("active_installed_model_id", null),
            selectedModelId = prefs.getString("selected_model_id", null),
        )
        migrateDownload(prefs.getLong("download_id", -1L), prefs.getString("download_source", null))
        migrateSettings(prefs)
        migrateRemote(prefs)
        settingsStore.setMigrationVersion(CURRENT_MIGRATION_VERSION)
    }

    private fun migrateSessions(encoded: String?, activeSessionId: String?) {
        if (database.sessionDao().sessions().isNotEmpty()) return
        val sessions = encoded?.let { decodeSessions(it) }.orEmpty()
        if (sessions.isEmpty()) return
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
        val active = sessions.firstOrNull { it.id == activeSessionId }?.id ?: sessions.first().id
        settingsStore.saveActiveSessionId(active)
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
            runCatching {
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

    private fun migrateDownload(downloadId: Long, sourceJson: String?) {
        if (downloadId <= 0L || sourceJson.isNullOrBlank() || database.downloadRecordDao().record() != null) return
        database.downloadRecordDao().upsert(
            DownloadRecordEntity(
                id = PENDING_DOWNLOAD_RECORD_ID,
                downloadManagerId = downloadId,
                sourceJson = sourceJson,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
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
        runCatching {
            settingsStore.saveInferenceMode(
                com.bytedance.zgx.solin.InferenceMode.valueOf(prefs.getString("inference_mode", "").orEmpty()),
            )
        }
        runCatching {
            settingsStore.saveBackend(
                com.bytedance.zgx.solin.BackendChoice.valueOf(prefs.getString("backend", "").orEmpty()),
            )
        }
    }

    private fun migrateRemote(prefs: android.content.SharedPreferences) {
        settingsStore.saveRemoteConfig(
            com.bytedance.zgx.solin.RemoteModelConfig(
                baseUrl = prefs.getString("remote_model_base_url", "").orEmpty(),
                modelName = prefs.getString("remote_model_name", "").orEmpty(),
            ),
        )
        val legacyKey = prefs.getString("remote_model_api_key", "").orEmpty()
        if (legacyKey.isNotBlank() && secretStore.saveString("remote_model_api_key", legacyKey).isSuccess) {
            prefs.edit().remove("remote_model_api_key").apply()
        }
    }

    private fun decodeSessions(encoded: String): List<LegacySession> =
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
        }.getOrDefault(emptyList())

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
