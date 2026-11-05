# Data Layer Suspend Function Migration Plan

> **Status: NOT STARTED** — `runBlocking` is still used in `PreferenceSettingsStore.kt`
> (and other data-layer call sites). This plan remains available for future execution.
>
> Line numbers in §1–§3.3 and §3.5–§3.7 were re-checked against the tree on 2026-11-05. The
> `SolinViewModel` call-site inventory in §3.4 after `createInitialState()` predates the
> controller split; most of those call sites now live in `presentation/*` controllers, so
> re-inventory them before Step 3.

## Overview

The Solin Android data layer currently uses `runBlocking` extensively to bridge synchronous interface
contracts with Jetpack DataStore's asynchronous (`Flow`-based) API. This blocks calling threads
(including the main thread in some paths), prevents structured concurrency, and makes error handling
and cancellation impossible. This plan migrates all data-layer interfaces to use `suspend` functions,
eliminating `runBlocking` from the data layer entirely.

**Scope**: 13 interfaces, 17+ implementations, ~170 methods, ~60 call sites in `SolinViewModel`,
plus call sites in the `presentation/*` controllers, `MemoryController`, `SolinAppContainer`, `LegacyPrefsMigrator`, and `SystemPromptBuilder`.

---

## 1. Complete List of Interfaces and Functions Needing `suspend`

### 1.1 `SessionStore` (`app/src/main/java/com/bytedance/zgx/solin/data/Stores.kt`, lines 15-25)

| Line | Current Signature | Target Signature | Rationale |
|------|-------------------|------------------|-----------|
| 16 | `val activeSessionId: String` | `suspend fun activeSessionId(): String` | Backed by Room + ActiveSessionStore I/O; property access cannot be `suspend` |
| 17 | `fun summaries(): List<ChatSessionSummary>` | `suspend fun summaries(): List<ChatSessionSummary>` | Room query `sessionDao.sessions()` |
| 18 | `fun activeMessages(): List<ChatMessage>` | `suspend fun activeMessages(): List<ChatMessage>` | Room query `sessionDao.messagesForSession(activeSessionId)` |
| 19 | `fun allMessages(limit: Int): List<ChatMessage>` | `suspend fun allMessages(limit: Int): List<ChatMessage>` | Room query across sessions |
| 20 | `fun createNewSession(): List<ChatMessage>` | `suspend fun createNewSession(): List<ChatMessage>` | Room insert + ActiveSessionStore write |
| 21 | `fun selectSession(sessionId: String): List<ChatMessage>?` | `suspend fun selectSession(sessionId: String): List<ChatMessage>?` | Room query + ActiveSessionStore write |
| 22 | `fun deleteActiveSession(): List<ChatMessage>?` | `suspend fun deleteActiveSession(): List<ChatMessage>?` | Room delete + ActiveSessionStore write |
| 23 | `fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean)` | `suspend fun replaceActiveSessionMessages(...)` | Room upsert + optional insert |
| 24 | `fun persistActiveSessionFrom(messages: List<ChatMessage>)` | `suspend fun persistActiveSessionFrom(messages: List<ChatMessage>)` | Room write |

### 1.2 `ActiveSessionStore` (`Stores.kt`, lines 27-30)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 28 | `fun activeSessionId(): String?` | `suspend fun activeSessionId(): String?` | DataStore read |
| 29 | `fun saveActiveSessionId(sessionId: String)` | `suspend fun saveActiveSessionId(sessionId: String)` | DataStore write |

### 1.3 `ModelRepositoryFacade` (`Stores.kt`, lines 32-58)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 33 | `fun currentState(): ModelSelectionState` | `suspend fun currentState(): ModelSelectionState` | Calls `settingsStore.selectedModelId()` + `settingsStore.activeInstalledModelId()` + Room queries |
| 35 | `fun selectedRecommendedModel(): RecommendedModel` | `suspend fun selectedRecommendedModel(): RecommendedModel` | Calls `settingsStore.selectedModelId()` |
| 36 | `fun selectRecommendedModel(modelId: String): ModelSelectionResult` | `suspend fun selectRecommendedModel(modelId: String): ModelSelectionResult` | Calls `settingsStore.saveSelectedModelId()` + `currentState()` |
| 37 | `fun selectInstalledModel(modelId: String): InstalledModelSummary?` | `suspend fun selectInstalledModel(modelId: String): InstalledModelSummary?` | Room query + `settingsStore.activeInstalledModelId()` |
| 38 | `fun deleteInstalledModel(modelId: String): Boolean` | `suspend fun deleteInstalledModel(modelId: String): Boolean` | Room delete + file I/O + `settingsStore.saveActiveInstalledModelId()` |
| 39-45 | `fun registerInstalledModel(...)` | `suspend fun registerInstalledModel(...)` | Room upsert + `settingsStore.saveActiveInstalledModelId()` + `settingsStore.saveSelectedModelId()` |
| 46 | `fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource?` | `suspend fun createCustomDownloadSource(...)` | Pure URL parsing; **low priority** but must match interface for consistency |
| 47 | `fun downloadedModelFile(fileName: String): File?` | `suspend fun downloadedModelFile(fileName: String): File?` | Pure computation; **low priority** |
| 48 | `fun pendingDownloadId(): Long` | `suspend fun pendingDownloadId(): Long` | Room query `downloadRecordDao.record()` |
| 49 | `fun savePendingDownload(downloadId: Long, source: ModelDownloadSource)` | `suspend fun savePendingDownload(...)` | Room upsert |
| 50 | `fun clearPendingDownload()` | `suspend fun clearPendingDownload()` | Room delete |
| 51 | `fun loadPendingDownloadSource(): ModelDownloadSource?` | `suspend fun loadPendingDownloadSource(): ModelDownloadSource?` | Room query |
| 52 | `fun resolveModelStorageBytes(): Long` | `suspend fun resolveModelStorageBytes(): Long` | File system stat; **low priority** (fast) |
| 53 | `fun verifiedActionModelPath(): String?` | `suspend fun verifiedActionModelPath(): String?` | Room queries + file existence checks |
| 54 | `fun verifiedObservationActionModelPath(): String?` | `suspend fun verifiedObservationActionModelPath(): String?` | Room queries + file existence checks |
| 55 | `fun verifiedMemoryEmbeddingModelPath(): String?` | `suspend fun verifiedMemoryEmbeddingModelPath(): String?` | Room queries + file existence checks |
| 56 | `fun verifyLegacyRecommendedModels(): Boolean` | `suspend fun verifyLegacyRecommendedModels(): Boolean` | Room queries + file I/O + SHA-256 verification |
| 57 | `fun importModel(uri: Uri, onProgress: ...): String` | `suspend fun importModel(uri: Uri, onProgress: ...): String` | File copy + Room upsert; already called inside `withContext` |

### 1.4 `GenerationParametersStore` (`Stores.kt`, lines 60-66)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 61 | `fun load(): GenerationParameters` | `suspend fun load(): GenerationParameters` | DataStore reads (4 keys) |
| 62 | `fun save(parameters: GenerationParameters): GenerationParameters` | `suspend fun save(parameters: GenerationParameters): GenerationParameters` | DataStore writes (uses `runBlocking` today) |
| 63 | `fun reset(): GenerationParameters` | `suspend fun reset(): GenerationParameters` | Delegates to `save()` |
| 64 | `fun loadBackend(): BackendChoice` | `suspend fun loadBackend(): BackendChoice` | DataStore read |
| 65 | `fun saveBackend(backend: BackendChoice)` | `suspend fun saveBackend(backend: BackendChoice)` | DataStore write |

### 1.5 `FirstRunSetupStore` (`Stores.kt`, lines 68-77)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 69 | `fun isSetupDismissed(): Boolean` | `suspend fun isSetupDismissed(): Boolean` | DataStore read |
| 70 | `fun markSetupDismissed()` | `suspend fun markSetupDismissed()` | DataStore write |
| 71 | `fun isMemoryEnabled(): Boolean` | `suspend fun isMemoryEnabled(): Boolean` | DataStore read |
| 72 | `fun setMemoryEnabled(enabled: Boolean)` | `suspend fun setMemoryEnabled(enabled: Boolean)` | DataStore write |
| 73 | `fun reduceDeviceActionConfirmations(): Boolean` | `suspend fun reduceDeviceActionConfirmations(): Boolean` | DataStore read |
| 74 | `fun setReduceDeviceActionConfirmations(enabled: Boolean)` | `suspend fun setReduceDeviceActionConfirmations(enabled: Boolean)` | DataStore write |

### 1.6 `RemoteModelStore` (`Stores.kt`, lines 79-89)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 80 | `fun loadMode(): InferenceMode` | `suspend fun loadMode(): InferenceMode` | DataStore read via SettingsStore |
| 81 | `fun saveMode(mode: InferenceMode): InferenceMode` | `suspend fun saveMode(mode: InferenceMode): InferenceMode` | DataStore write via SettingsStore |
| 82 | `fun loadConfig(): RemoteModelConfig` | `suspend fun loadConfig(): RemoteModelConfig` | DataStore read + SecretStore read (API key) |
| 83 | `fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig>` | `suspend fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig>` | SecretStore write + DataStore write |
| 84-85 | `fun saveConfigWithoutApiKey(config: RemoteModelConfig): Result<RemoteModelConfig>` | `suspend fun saveConfigWithoutApiKey(...): Result<RemoteModelConfig>` | DataStore write |

### 1.7 `RemoteSendPendingStore` (`Stores.kt`, lines 91-95)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 92 | `fun savePendingRemoteSend(marker: PendingRemoteSendMarker)` | `suspend fun savePendingRemoteSend(marker: PendingRemoteSendMarker)` | DataStore write (JSON serialization + string write) |
| 93 | `fun consumePendingRemoteSend(): PendingRemoteSendMarker?` | `suspend fun consumePendingRemoteSend(): PendingRemoteSendMarker?` | DataStore read + write (read-then-clear) |
| 94 | `fun clearPendingRemoteSend()` | `suspend fun clearPendingRemoteSend()` | DataStore write |

### 1.8 `SettingsStore` (`Stores.kt`, lines 97-118)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 98 | `fun isSetupDismissed(): Boolean` | `suspend fun isSetupDismissed(): Boolean` | DataStore read |
| 99 | `fun markSetupDismissed()` | `suspend fun markSetupDismissed()` | DataStore write |
| 100 | `fun isMemoryEnabled(): Boolean` | `suspend fun isMemoryEnabled(): Boolean` | DataStore read |
| 101 | `fun setMemoryEnabled(enabled: Boolean)` | `suspend fun setMemoryEnabled(enabled: Boolean)` | DataStore write |
| 102 | `fun reduceDeviceActionConfirmations(): Boolean` | `suspend fun reduceDeviceActionConfirmations(): Boolean` | DataStore read |
| 103 | `fun setReduceDeviceActionConfirmations(enabled: Boolean)` | `suspend fun setReduceDeviceActionConfirmations(enabled: Boolean)` | DataStore write |
| 106 | `fun loadGenerationParameters(): GenerationParameters` | `suspend fun loadGenerationParameters(): GenerationParameters` | DataStore reads (4 keys) |
| 107 | `fun saveGenerationParameters(parameters: GenerationParameters): GenerationParameters` | `suspend fun saveGenerationParameters(...): GenerationParameters` | DataStore write (uses `runBlocking` at line 83 today) |
| 108 | `fun loadBackend(): BackendChoice` | `suspend fun loadBackend(): BackendChoice` | DataStore read |
| 109 | `fun saveBackend(backend: BackendChoice)` | `suspend fun saveBackend(backend: BackendChoice)` | DataStore write |
| 110 | `fun loadInferenceMode(): InferenceMode` | `suspend fun loadInferenceMode(): InferenceMode` | DataStore read |
| 111 | `fun saveInferenceMode(mode: InferenceMode): InferenceMode` | `suspend fun saveInferenceMode(mode: InferenceMode): InferenceMode` | DataStore write |
| 112 | `fun loadRemoteConfig(apiKey: String): RemoteModelConfig` | `suspend fun loadRemoteConfig(apiKey: String): RemoteModelConfig` | DataStore reads (5 keys) |
| 113 | `fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig` | `suspend fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig` | DataStore write (uses `runBlocking` at line 149 today) |
| 114 | `fun selectedModelId(): String?` | `suspend fun selectedModelId(): String?` | DataStore read |
| 115 | `fun saveSelectedModelId(modelId: String)` | `suspend fun saveSelectedModelId(modelId: String)` | DataStore write |
| 116 | `fun activeInstalledModelId(): String?` | `suspend fun activeInstalledModelId(): String?` | DataStore read |
| 117 | `fun saveActiveInstalledModelId(modelId: String?)` | `suspend fun saveActiveInstalledModelId(modelId: String?)` | DataStore write |

### 1.9 `SecretStore` (`Stores.kt`, lines 120-123)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 121 | `fun loadString(name: String): Result<String>` | `suspend fun loadString(name: String): Result<String>` | EncryptedSharedPreferences read |
| 122 | `fun saveString(name: String, value: String): Result<Unit>` | `suspend fun saveString(name: String, value: String): Result<Unit>` | EncryptedSharedPreferences write |

### 1.10 `HuggingFaceAuthStore` (`app/src/main/java/com/bytedance/zgx/solin/data/HuggingFaceAuthRepository.kt`, lines 5-10)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 6 | `fun hasAccessToken(): Boolean` | `suspend fun hasAccessToken(): Boolean` | Delegates to `SecretStore.loadString` |
| 7 | `fun authorizationHeader(): String?` | `suspend fun authorizationHeader(): String?` | Delegates to `SecretStore.loadString` |
| 8 | `fun saveAccessToken(token: String): Result<Unit>` | `suspend fun saveAccessToken(token: String): Result<Unit>` | Delegates to `SecretStore.saveString` |
| 9 | `fun clearAccessToken(): Result<Unit>` | `suspend fun clearAccessToken(): Result<Unit>` | Delegates to `SecretStore.saveString` |

### 1.11 `MemoryRecordStore` (`app/src/main/java/com/bytedance/zgx/solin/memory/MemoryStores.kt`, lines 39-44)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 40 | `fun records(): List<PersistedMemoryRecord>` | `suspend fun records(): List<PersistedMemoryRecord>` | Room query or LocalDocumentStore list |
| 41 | `fun upsert(record: PersistedMemoryRecord)` | `suspend fun upsert(record: PersistedMemoryRecord)` | Room upsert or document write |
| 42 | `fun delete(id: String): Boolean` | `suspend fun delete(id: String): Boolean` | Room delete or document delete |
| 43 | `fun clear()` | `suspend fun clear()` | Room deleteAll or document delete |

### 1.12 `MemoryDeletionEventStore` (`MemoryStores.kt`, lines 46-66)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 47 | `fun events(): List<MemoryDeletionEvent>` | `suspend fun events(): List<MemoryDeletionEvent>` | Room query |
| 48 | `fun append(event: MemoryDeletionEvent)` | `suspend fun append(event: MemoryDeletionEvent)` | Room insert |
| 49-57 | `fun deleteRecordAndAppendEvent(...)` | `suspend fun deleteRecordAndAppendEvent(...)` | Room transaction (delete + insert) |
| 59-65 | `fun clearRecordsAndAppendEvents(...)` | `suspend fun clearRecordsAndAppendEvents(...)` | Room transaction (clearAll + insert) |

### 1.13 `MemoryEmbeddingStore` (`MemoryStores.kt`, lines 77-83)

| Line | Current | Target | Rationale |
|------|---------|--------|-----------|
| 78 | `fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding?` | `suspend fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding?` | Room query or vector index lookup |
| 79 | `fun upsert(embedding: PersistedMemoryEmbedding)` | `suspend fun upsert(embedding: PersistedMemoryEmbedding)` | Room upsert or vector write |
| 80 | `fun delete(recordId: String)` | `suspend fun delete(recordId: String)` | Room delete or vector delete |
| 81 | `fun deleteForModel(modelId: String)` | `suspend fun deleteForModel(modelId: String)` | Room delete or vector delete |
| 82 | `fun clear()` | `suspend fun clear()` | Room deleteAll or vector clear |

### 1.14 Higher-Level Memory Interfaces (`MemoryRepository.kt`)

These interfaces do not directly perform I/O but their implementations call the stores above.
They become `suspend` transitively:

- **`MemoryIndex`** (lines 92-98): `rebuild()` calls `recordStore.records()`; `index()` calls `recordStore.upsert()` via `indexRecord()`
- **`LongTermMemoryControls`** (lines 100-113): all methods delegate to MemoryRecordStore/EmbeddingStore
- **`SemanticMemoryRuntimeController`** (lines 115-125): `useMemoryModel()` calls `embeddingStore.deleteForModel()` and `runtime.embed()`; `clearSemanticMemoryForModel()` calls `embeddingStore.deleteForModel()`

### 1.15 `EmbeddingRuntime` (`MemoryRepository.kt`, lines 65-90)

`embed()` and `embedBatch()` are CPU-bound operations. They do not need to become `suspend` since
they are pure computation; callers should use `withContext(Dispatchers.Default)` when invoking them.
However, if the runtime factory (`runtimeFactory(path)`) involves file I/O (model loading), that
initialization path should be `suspend`.

---

## 2. Complete List of Implementations Needing Updates

### 2.1 `PreferenceSettingsStore` (`data/PreferenceSettingsStore.kt`)

- **Implements**: `SettingsStore`, `ActiveSessionStore`, `RemoteSendPendingStore`
- **Key changes**:
  - Remove all 4 `runBlocking` usages (lines 82, 147, 213, 219)
  - Convert private helpers to `suspend fun`: `snapshot` (line 212), `read` (215), `write` (218)
  - All 25 public override functions become `suspend fun`
  - `migrationVersion()` (line 204) and `setMigrationVersion()` (line 207) are not part of any interface but also use `runBlocking`; make them `suspend` too

### 2.2 `SessionRepository` (`data/SessionRepository.kt`)

- **Implements**: `SessionStore`
- **Key changes**:
  - `activeSessionId` property (line 22) becomes a `suspend fun` or is backed by a `suspend` initializer
  - `resolveActiveSessionId()` (line 112) becomes `suspend`
  - All Room DAO calls become `suspend` (Room DAOs already support suspend natively)
  - `summaries()` (line 25), `activeMessages()` (37), `allMessages()` (40), `createNewSession()` (51), `selectSession()` (59), `deleteActiveSession()` (66), `replaceActiveSessionMessages()` (74), `persistActiveSessionFrom()` (91) all become `suspend`

### 2.3 `ModelRepository` (`data/ModelRepository.kt`)

- **Implements**: `ModelRepositoryFacade`
- **Key changes**:
  - `init` block (lines 240-243) calls `cleanTemporaryModelFiles()` and `discoverRecommendedDownloadedModels()` which do file I/O + Room I/O; needs restructuring to `CoroutineScope.launch` or a `suspend fun initialize()`
  - All methods calling `settingsStore.*` become `suspend`: `currentState()` (245), `selectedRecommendedModel()` (268), `selectRecommendedModel()` (271), `selectInstalledModel()` (281), `deleteInstalledModel()` (287), `registerInstalledModel()` (316), `pendingDownloadId()` (379), `savePendingDownload()` (382), `clearPendingDownload()` (393), `loadPendingDownloadSource()` (397), `verifiedActionModelPath()` (419), `verifiedObservationActionModelPath()` (427), `verifiedMemoryEmbeddingModelPath()` (435), `verifyLegacyRecommendedModels()` (443), `importModel()` (355)
  - Private helpers become `suspend`: `activeInstalledModel()` (512), `activateInstalledModel()` (522), `installedModels()` (529), `installedModelSummaries()` (532), `refreshCompletedRecommendedBundles()` (620), `markRecommendedVerificationFailed()` (639)

### 2.4 `RemoteModelRepository` (`data/RemoteModelRepository.kt`)

- **Implements**: `RemoteModelStore`
- **Key changes**:
  - `loadMode()` (line 38), `saveMode()` (41), `loadConfig()` (44), `saveConfig()` (58), `saveConfigWithoutApiKey()` (118) all become `suspend`
  - `loadApiKey()` (line 240) becomes `suspend` (calls `secretStore.loadString()` and `secretStore.saveString()`)

### 2.5 `GenerationParametersRepository` (`data/GenerationParametersRepository.kt`)

- **Implements**: `GenerationParametersStore`
- **Key changes**: `load()` (line 10), `save()` (13), `reset()` (16), `loadBackend()` (19), `saveBackend()` (22) all become `suspend` (delegate to SettingsStore)

### 2.6 `FirstRunSetupRepository` (`data/FirstRunSetupRepository.kt`)

- **Implements**: `FirstRunSetupStore`
- **Key changes**: `isSetupDismissed()` (line 6), `markSetupDismissed()` (9), `isMemoryEnabled()` (13), `setMemoryEnabled()` (16), `reduceDeviceActionConfirmations()` (20), `setReduceDeviceActionConfirmations()` (23) all become `suspend` (delegate to SettingsStore)

### 2.7 `EncryptedSecretStore` (`data/EncryptedSecretStore.kt`)

- **Implements**: `SecretStore`
- **Key changes**: `loadString()` (line 10) and `saveString()` (line 15) become `suspend`. EncryptedSharedPreferences `.commit()` is blocking but fast; can use `withContext(Dispatchers.IO)` internally.

### 2.8 `HuggingFaceAuthRepository` (`data/HuggingFaceAuthRepository.kt`)

- **Implements**: `HuggingFaceAuthStore`
- **Key changes**: `hasAccessToken()` (line 15), `authorizationHeader()` (18), `saveAccessToken()` (23), `clearAccessToken()` (32) all become `suspend`. `loadAccessToken()` (line 35) becomes `suspend`.

### 2.9 `MemoryRepository` (`memory/MemoryRepository.kt`)

- **Implements**: `MemoryIndex`, `LongTermMemoryControls`, `SemanticMemoryRuntimeController`
- **Key changes**:
  - `rebuild()` (line 252) becomes `suspend` (calls `visibleRecords()` -> `recordStore.records()`)
  - `index()` (line 289) becomes `suspend` (calls `indexRecord()` -> `recordStore.upsert()`)
  - `savedRecords()` (line 300) becomes `suspend` (calls `visibleRecords()`)
  - `indexPreference()` (304), `indexUserFact()` (319), `indexTaskState()` (334) become `suspend`
  - `suppressAutoManagedTaskState()` (348), `unsuppressAutoManagedTaskState()` (367), `isAutoManagedTaskStateSuppressed()` (371) become `suspend`
  - `forget()` (378), `forgetAutoManagedTaskState()` (382), `forgetPreference()` (387), `forgetUserFact()` (399), `clear()` (410) become `suspend`
  - `useMemoryModel()` (line 210) becomes `suspend` (calls `embeddingStore.deleteForModel()` via `clearSemanticMemoryForModel()` and `reembedEntries()`)
  - `clearSemanticMemoryForModel()` (line 200) becomes `suspend`
  - Private helpers become `suspend`: `indexRecord()` (428), `visibleRecords()` (425), `forgetConflictingPreferences()` (640), `forgetConflictingUserFacts()` (648), `forget()` (671), `forgetWithoutDeletionAudit()` (695), `recordForDeletionAudit()` (703), `deletionAuditableRecords()` (710), `semanticEmbeddingFor()` (551), `reembedEntries()` (597), `refreshSemanticIndexStats()` (612)

### 2.10 `NoOpMemoryRecordStore` (`MemoryStores.kt`, line 85)

- Add `suspend` modifier to all overrides (trivial since they are no-ops)

### 2.11 ~~`ZvecMemoryRecordStore`~~ (deleted)

- Removed on 2026-10-30 (`a57bc0ee`); production memory records live only in encrypted Room.

### 2.12 `RoomMemoryRecordStore` (`RoomMemoryStores.kt`, line 18)

- **Implements**: `MemoryRecordStore`
- All DAO calls become `suspend`

### 2.13 `NoOpMemoryEmbeddingStore` (`MemoryStores.kt`, line 92)

- Add `suspend` modifier to all overrides

### 2.14 ~~`ZvecMemoryEmbeddingStore`~~ (deleted)

- Removed on 2026-10-30 (`a57bc0ee`) together with `ZvecMemoryRecordStore`.

### 2.15 `RoomMemoryEmbeddingStore` (`RoomMemoryStores.kt`, line 135)

- **Implements**: `MemoryEmbeddingStore`
- All DAO calls become `suspend`

### 2.16 `NoOpMemoryDeletionEventStore` (`MemoryStores.kt`, line 100)

- Add `suspend` modifier to all overrides

### 2.17 `RoomMemoryDeletionEventStore` (`RoomMemoryStores.kt`, line 68)

- **Implements**: `MemoryDeletionEventStore`
- All DAO calls become `suspend`

---

## 3. Complete List of Call Sites Using `runBlocking` or Manual `withContext`

### 3.1 `runBlocking` in Data Layer Implementations

| File | Line | Context |
|------|------|---------|
| `PreferenceSettingsStore.kt` | 82 | `saveGenerationParameters`: `runBlocking { dataStore.edit { prefs -> ... } }` |
| `PreferenceSettingsStore.kt` | 147 | `saveRemoteConfig`: `runBlocking { dataStore.edit { prefs -> ... } }` |
| `PreferenceSettingsStore.kt` | 213 | `snapshot` private helper: `runBlocking { dataStore.data.first() }` (used by `read`) |
| `PreferenceSettingsStore.kt` | 219 | `write` private helper: `runBlocking { dataStore.edit { it[key] = value } }` |

### 3.2 `runBlocking` in Non-Data-Layer Code (Not Directly In Scope But Documented)

| File | Line | Context |
|------|------|---------|
| `runtime/LiteRtRuntime.kt` | 443, 523 | Context compaction (`compactor.compact()` is already `suspend`) and one-shot generation. Not data layer I/O. |
| `action/ActionPlanningRuntime.kt` | 145 | Model inference streaming. Not data layer. |
| `orchestration/SystemPromptBuilder.kt` | 129 | `buildSystemPromptBlocking` convenience wrapper. Already has `suspend fun buildSystemPrompt()`. |
| `tool/ToolExecutor.kt` | 140, 197 | Tool handler execution. Not data layer. |
| `tool/DeviceControlToolExecutor.kt` | 77, 722 | Device observation / action bridge. Not data layer. |
| `orchestration/AndroidRemoteVisionDecider.kt` | 151 | Remote vision request with timeout. Not data layer. |

### 3.3 Manual `withContext` Wrapping Data Calls

| File | Line | Context |
|------|------|---------|
| `presentation/ModelLoadController.kt` | 679 | `withContext(ioDispatcher) { ... modelRepository.importModel(uri) { ... } }` -- importModel already does heavy I/O |
| `MemoryController.kt` | 466 | `syncSemanticMemoryRuntime` calls `modelRepository.verifiedMemoryEmbeddingModelPath()` (sync; no `withContext` wrapper in `MemoryController` today, see §3.7) |

### 3.4 Critical Synchronous Call Sites in `SolinViewModel`

The most impactful call site is `InitialChatStateFactory.createInitialState()`
(`presentation/StartupStateRestorer.kt`, line 45), invoked during ViewModel property
initialization unless persistence initialization is deferred:

```kotlin
// SolinViewModel.kt line 229
private val _uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(
    if (deferPersistenceInitialization) ChatUiState(statusText = "正在初始化本地数据", ...)
    else initialChatStateFactory.createInitialState(),
)
```

`createInitialState()` (`StartupStateRestorer.kt`) synchronously calls:
- `modelRepository.currentState()` (line 46)
- `generationParametersRepository.loadBackend()` (47)
- `firstRunSetupRepository.isMemoryEnabled()` (48)
- `firstRunSetupRepository.reduceDeviceActionConfirmations()` (49)
- `firstRunSetupRepository.remoteGuiAutomationEnabled()` (50)
- `remoteModelRepository.loadMode()` (51) and, when the mode changes, `saveMode()` (53)
- `remoteModelRepository.loadConfig()` (54)
- `firstRunSetupRepository.isSetupDismissed()` (56)
- `memoryRepository.enabled = memoryEnabled` (57)
- `memoryController.syncTaskStateMemories(memoryEnabled)` (58) -- calls `longTermMemoryControls.savedRecords()`
- `memoryController.semanticMemoryState()` (59)
- `huggingFaceAuthStore.hasAccessToken()` (77)
- `memoryController.loadLongTermMemories()` (78) -- calls `longTermMemoryControls.savedRecords()`
- `generationParametersRepository.load()` (85)
- `sessionRepository.summaries()` (86)
- `sessionRepository.activeSessionId` (87) -- property access
- `sessionRepository.activeMessages()` (88)
- `modelRepository.resolveModelStorageBytes()` (90)

Additional synchronous call sites in the ViewModel (called from `viewModelScope.launch` blocks
but calling sync methods directly -- each must be audited):

**modelRepository calls:**
- `pendingDownloadId()` / `loadPendingDownloadSource()` / `clearPendingDownload()` -- lines 291-300
- `selectedRecommendedModel()` -- lines 294, 432, 1108
- `selectRecommendedModel()` -- lines 296, 438, 1100
- `downloadedModelFile()` -- lines 298, 4451
- `currentState()` -- lines 314, 348, 351, 887, 1122, 1157, 4665, 4751, 5025, 5125
- `createCustomDownloadSource()` -- line 812
- `selectInstalledModel()` -- line 1116
- `deleteInstalledModel()` -- line 1151
- `savePendingDownload()` -- line 4531
- `registerInstalledModel()` -- lines 4653, 4738
- `verifyLegacyRecommendedModels()` -- line 5123 (already in `viewModelScope.launch(ioDispatcher)`)
- `verifiedActionModelPath()` -- line 1701
- `verifiedMemoryEmbeddingModelPath()` -- line 5251
- `resolveModelStorageBytes()` -- lines 5067, 5197
- `importModel()` -- line 870 (already in `withContext(ioDispatcher)`)

**sessionRepository calls:**
- `activeMessages()` -- lines 1229, 1268, 4807, 5065
- `summaries()` -- lines 1338, 1414, 5063, 5649, 5661
- `activeSessionId` (property) -- lines 1334, 1371, 1409, 4806, 5064, 5571, 5604, 5650, 5662
- `createNewSession()` -- line 1333
- `selectSession()` -- line 1370
- `deleteActiveSession()` -- line 1408
- `allMessages()` -- line 5214
- `replaceActiveSessionMessages()` -- lines 1885, 2433, 2488, 2723, 2746, 2769, 2791, 2820, 5646
- `persistActiveSessionFrom()` -- line 5658

**generationParametersRepository calls:**
- `saveBackend()` -- lines 926, 1198
- `save()` -- line 938
- `loadBackend()` -- line 5026
- `load()` -- line 5062

**firstRunSetupRepository calls:**
- `markSetupDismissed()` -- lines 349, 400, 496, 530, 886, 1034, 4486, 4750, 4795, 5032
- `isMemoryEnabled()` -- line 5027
- `setMemoryEnabled()` -- line 540
- `reduceDeviceActionConfirmations()` -- line 5028
- `setReduceDeviceActionConfirmations()` -- line 555
- `isSetupDismissed()` -- line 5032

**remoteModelRepository calls:**
- `loadMode()` -- line 5029
- `saveMode()` -- lines 401, 962, 1117, 1193
- `loadConfig()` -- line 5030
- `saveConfig()` -- lines 1022, 1079
- `saveConfigWithoutApiKey()` -- line 397

**huggingFaceAuthStore calls:**
- `hasAccessToken()` -- lines 449, 4557, 5054, 5109
- `saveAccessToken()` -- line 445
- `clearAccessToken()` -- line 463

**remoteSendPendingStore calls:**
- `clearPendingRemoteSend()` -- lines 958, 1008, 1120, 1196, 1330, 1367, 1403, 2327, 2377, 2411, 2588, 5151
- `savePendingRemoteSend()` -- line 2465
- `consumePendingRemoteSend()` -- line 2479

**memoryRepository / longTermMemoryControls calls:**
- `memoryRepository.enabled = ...` -- lines 541, 5033, 5213
- `memoryRepository.rebuild()` -- line 5214
- `longTermMemoryControls.savedRecords()` -- lines 5276, 5307 (in `loadLongTermMemories()` and `syncTaskStateMemories()`)
- `longTermMemoryControls.forget()` -- line 570
- `longTermMemoryControls.suppressAutoManagedTaskState()` -- lines 572, 590
- `longTermMemoryControls.unsuppressAutoManagedTaskState()` -- line 644
- `longTermMemoryControls.clear()` -- line 588
- `longTermMemoryControls.isAutoManagedTaskStateSuppressed()` -- line 5286
- `longTermMemoryControls.indexTaskState()` -- line 5287
- `longTermMemoryControls.indexPreference()` -- line 5326
- `longTermMemoryControls.indexUserFact()` -- line 5335
- `longTermMemoryControls.forgetPreference()` -- line 5439
- `longTermMemoryControls.forgetUserFact()` -- line 5440
- `longTermMemoryControls.forgetAutoManagedTaskState()` -- line 5282

### 3.5 Call Sites in `SolinAppContainer`

| Line | Context |
|------|---------|
| 238 | `LegacyPrefsMigrator(appContext, database, settingsStore, secretStore).migrateIfNeeded()` -- called during construction; `LegacyPrefsMigrator` calls 10+ settingsStore methods + secretStore |
| 403 | `modelRepository.verifiedObservationActionModelPath()` -- called as lambda provider for `appSearchPlanningModeProvider` |
| 444 | `modelRepository::verifiedObservationActionModelPath` -- method reference as `actionModelPathProvider` |
| 497 | `remoteSendPendingStore = settingsStore` -- wires settingsStore as RemoteSendPendingStore implementation |
| 291 | `LegacyMemoryStorageRetirement(...).retireAfterSecureImport()` -- reads and upserts Room memory records during construction (replaced `backfillRoomMemoryRecords()`) |

### 3.6 Call Sites in `LegacyPrefsMigrator`

| Line | Context |
|------|---------|
| 31 | `settingsStore.migrationVersion()` |
| 69 | `settingsStore.setMigrationVersion()` |
| 109 | `settingsStore.saveActiveSessionId()` |
| 119 | `settingsStore.saveSelectedModelId()` |
| 121 | `settingsStore.saveActiveInstalledModelId()` |
| 213 | `settingsStore.setMemoryEnabled()` |
| 214 | `settingsStore.markSetupDismissed()` |
| 215 | `settingsStore.saveGenerationParameters()` |
| 222 | `settingsStore.saveInferenceMode()` |
| 226 | `settingsStore.saveBackend()` |
| 233 | `settingsStore.saveRemoteConfig()` |
| 241 | `secretStore.saveString(LEGACY_REMOTE_API_KEY, legacyKey)` |

### 3.7 Call Sites in `MemoryController`

`MemoryController`'s public methods are synchronous today; callers dispatch them onto IO
(`launchPersistenceWork`), and its `ioDispatcher` parameter is unused, kept for this
migration. After migration, its methods become `suspend` and call the suspend data APIs
directly; CPU-bound work (embedding computation, text processing) should still use an
explicit dispatcher.

Key call sites that become pure `suspend` calls:
- Line 122, 124: `longTermMemoryControls.forget()` + `suppressAutoManagedTaskState()`
- Lines 147, 149: `longTermMemoryControls.clear()` + `suppressAutoManagedTaskState()`
- Line 172: `memoryIndex.rebuild(sessionStore.allMessages(limit = 500))`
- Lines 200, 227: `longTermMemoryControls.savedRecords()`
- Lines 234-242: `longTermMemoryControls.forgetAutoManagedTaskState()`, `isAutoManagedTaskStateSuppressed()`, `indexTaskState()`
- Lines 261, 279: `longTermMemoryControls.indexPreference()` / `indexUserFact()`
- Lines 396-397: `longTermMemoryControls.forgetPreference()` + `forgetUserFact()`
- Lines 426, 433: `sessionStore.replaceActiveSessionMessages()` + `sessionStore.activeMessages()`
- Line 466: `modelRepository.verifiedMemoryEmbeddingModelPath()`

---

## 4. Migration Strategy in 4 Steps

### Step 1: Add Suspend Variants Alongside Sync

**Goal**: Introduce `suspend` versions of all interface methods while keeping the existing
synchronous API intact. No callers are changed yet. The app compiles and all tests pass.

**Actions**:

1. **In `Stores.kt`**: For each interface, add `suspend` variants. Use the naming convention where
   the `suspend` version gets the clean name and the sync version gets a `Blocking` suffix:
   ```kotlin
   interface SettingsStore {
       suspend fun isSetupDismissed(): Boolean
       @Deprecated("Use suspend isSetupDismissed()", level = WARNING)
       fun isSetupDismissedBlocking(): Boolean
       // ...
   }
   ```
   *Alternative for Step 1*: Keep interfaces unchanged and add `suspend` extension functions
   (e.g., `suspend fun SettingsStore.isSetupDismissedSuspend()`). This is less invasive but
   means the "good" API has an ugly name.

2. **In `PreferenceSettingsStore.kt`**:
   - Convert private helpers to `suspend fun` that call `dataStore.data.first()` and
     `dataStore.edit {}` directly (no `runBlocking`).
   - Implement new `suspend` public methods that call the suspend private helpers.
   - Keep existing sync methods but have them delegate via `runBlocking { suspendImpl() }`.
   - This centralizes `runBlocking` to one call per public method rather than scattering it.

3. **In all Repository classes** (`SessionRepository`, `ModelRepository`, `GenerationParametersRepository`,
   `FirstRunSetupRepository`, `RemoteModelRepository`, `HuggingFaceAuthRepository`):
   - Add `suspend` wrappers for all methods.
   - `ModelRepository.init` block remains sync for now.
   - `SessionRepository.activeSessionId` property: add a `suspend fun activeSessionIdSuspend()`
     alongside the property.

4. **In Memory layer** (`MemoryRepository.kt`):
   - Add `suspend` wrappers for MemoryRecordStore, MemoryEmbeddingStore, MemoryDeletionEventStore.
   - NoOp implementations get trivial `suspend` overrides.
   - `MemoryRepository` (the main implementation): add suspend variants for `rebuild`, `index`,
     `savedRecords`, `indexPreference`, `indexUserFact`, `indexTaskState`, `suppressAutoManagedTaskState`,
     `unsuppressAutoManagedTaskState`, `isAutoManagedTaskStateSuppressed`, `forget`, `forgetAutoManagedTaskState`,
     `forgetPreference`, `forgetUserFact`, `clear`, `useMemoryModel`, `clearSemanticMemoryForModel`.

5. **In `EncryptedSecretStore.kt`**: Add `suspend` variants using `withContext(Dispatchers.IO)`.

**Deliverable**: All interfaces have both `suspend fun foo()` and `fun fooBlocking()`. All
implementations provide both. No existing call sites are modified. The app compiles and all
tests pass.

---

### Step 2: Update Internal Data-Layer Callers to Use Suspend

**Goal**: Migrate all internal data-layer callers to use the `suspend` API. This means:
- `PreferenceSettingsStore` private helpers stop using `runBlocking` entirely.
- `ModelRepository` internal methods call `settingsStore.suspendMethod()` instead of sync methods.
- `MemoryRepository` internal methods call the suspend store APIs.
- `SessionRepository` calls suspend `activeSessionStore` methods.

**Actions**:

1. **`PreferenceSettingsStore.kt`**: Remove `runBlocking` from private helpers. The sync
   `*Blocking` methods each use a single `runBlocking { suspendImpl() }` wrapper.

2. **`ModelRepository.kt`**: Convert all private helper methods to `suspend`:
   - `activeInstalledModel()` -> `suspend fun`
   - `activateInstalledModel()` -> `suspend fun`
   - `installedModels()` -> `suspend fun`
   - `installedModelSummaries()` -> `suspend fun`
   - `refreshCompletedRecommendedBundles()` -> `suspend fun`
   - `markRecommendedVerificationFailed()` -> `suspend fun`
   - `discoverRecommendedDownloadedModels()` -> `suspend fun`

   The `init` block (lines 240-243) becomes a `CoroutineScope`-launched initialization:
   ```kotlin
   private val initScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
   init {
       initScope.launch {
           cleanTemporaryModelFiles()
           discoverRecommendedDownloadedModels()
       }
   }
   ```
   Or better: expose a `suspend fun initialize()` and let `SolinAppContainer` call it.

3. **`SessionRepository.kt`**: `resolveActiveSessionId()` (line 112) becomes `suspend`. The
   `activeSessionId` property initialization must be deferred:
   ```kotlin
   private var _activeSessionId: String? = null
   override suspend fun activeSessionId(): String {
       if (_activeSessionId == null) {
           _activeSessionId = resolveActiveSessionId()
       }
       return _activeSessionId!!
   }
   // Keep sync property for backward compat during migration:
   override val activeSessionId: String
       get() = runBlocking { activeSessionIdSuspend() }
   ```

4. **`MemoryRepository.kt`**: Convert `indexRecord()`, `rebuild()`, `visibleRecords()`,
   `forgetConflictingPreferences()`, `forgetConflictingUserFacts()`, `forget()`,
   `forgetWithoutDeletionAudit()`, `recordForDeletionAudit()`, `deletionAuditableRecords()`,
   `semanticEmbeddingFor()`, `reembedEntries()`, `refreshSemanticIndexStats()`,
   `clearSemanticMemoryForModel()`, `useMemoryModel()` to `suspend`.

   **Thread safety note**: `MemoryRepository` uses a `linkedMapOf<String, MemoryEntry>()` (line 172)
   which is not thread-safe. Add `Mutex` protection for all mutations of `entries`, or switch to
   `ConcurrentHashMap`.

5. **`MemoryController.kt`**: Make the public methods `suspend` and call the suspend data APIs
   directly (it has no `withContext` wrappers today). Use an explicit dispatcher only for
   CPU-bound work (embedding computation in `rebuildMemoryIndex`).

6. **`RemoteModelRepository.kt`**: `loadApiKey()` (line 240) becomes `suspend`.

7. **`LegacyPrefsMigrator.kt`**: `migrateIfNeeded()` becomes `suspend`. Called from
   `SolinAppContainer` init; wrap in `runBlocking` for now (one-time startup cost).

**Deliverable**: Zero `runBlocking` calls inside `PreferenceSettingsStore` private helpers.
All data-layer-internal code paths use `suspend`. The sync `*Blocking` methods are thin
`runBlocking { suspendImpl() }` wrappers. App compiles and tests pass.

---

### Step 3: Deprecate Sync Variants and Update ViewModel

**Goal**: Mark all synchronous `*Blocking` methods as `@Deprecated` with level `WARNING`.
Migrate `SolinViewModel` and `SolinAppContainer` call sites to use the `suspend` API.

**Actions**:

1. **`SolinViewModel.kt` -- `createInitialState()` migration** (CRITICAL):
   Currently called during property initialization:
   ```kotlin
   private val _uiState = MutableStateFlow(createInitialState())
   ```
   **Strategy**: Change to a two-phase initialization:
   ```kotlin
   private val _uiState = MutableStateFlow(ChatUiState.initialLoadingState())

   init {
       viewModelScope.launch {
           val state = createInitialState() // now a suspend function
           _uiState.value = state
           // After state is loaded, proceed with startup restoration
           restoreStartupState(skipModelRuntimeWork)
       }
   }
   ```
   `ChatUiState` needs a sensible loading/initial state (e.g., empty lists, null model path,
   "Loading..." status text, `isInitializing = true` flag). The UI must handle this transient
   loading state (show a loading indicator, disable interactive elements).

2. **`SolinViewModel.kt` -- all other call sites**:
   Every method that calls data layer APIs must become `suspend` or be called from a
   `viewModelScope.launch` block. Most already are (called from `viewModelScope.launch(ioDispatcher)`).
   The key change is replacing sync calls with `suspend` versions.

   For methods currently using `modelRepository.currentState()` directly (not inside a coroutine),
   they must be refactored to either:
   - Be called from within a `viewModelScope.launch` block, or
   - Cache the state in a `StateFlow` that is updated via suspend calls.

   `ModelLoadController.updateModelState()` (line 1073) is fed by `modelRepository.currentState()`
   at its callers; those calls should run from a coroutine context.

3. **`SolinAppContainer.kt`**:
   - The lambda providers (lines 403, 444) that call `modelRepository.verifiedObservationActionModelPath()`
     must become `suspend` lambdas or the provider interface must accept suspend functions.
   - `LegacyPrefsMigrator.migrateIfNeeded()` call at line 238: wrap in `runBlocking` for now
     (one-time startup migration, acceptable blocking).
   - `LegacyMemoryStorageRetirement.retireAfterSecureImport()` (line 291): called during init;
     wrap in `runBlocking` or launch in a background scope.

4. **Add `@Deprecated` annotations** to all `*Blocking` methods:
   ```kotlin
   @Deprecated(
       message = "Use suspend load() instead",
       replaceWith = ReplaceWith("load()"),
       level = DeprecationLevel.WARNING
   )
   fun loadBlocking(): GenerationParameters
   ```

5. **`SystemPromptBuilder.kt`**: The `buildSystemPromptBlocking()` method (line 94) can be
   deprecated since `buildSystemPrompt()` is already `suspend`.

**Deliverable**: All `*Blocking` methods show deprecation warnings. `SolinViewModel` uses
`suspend` APIs exclusively (except where a `runBlocking` bridge is genuinely needed, e.g.,
if called from a non-coroutine thread). `createInitialState()` is now `suspend` and the
ViewModel handles the loading state.

---

### Step 4: Remove Sync Variants

**Goal**: Delete all `*Blocking` methods and the `runBlocking` bridges. The data layer is
fully suspend-only.

**Actions**:

1. Remove all `*Blocking` methods from interfaces in `Stores.kt`.
2. Remove all `*Blocking` implementations from all concrete classes.
3. Remove `import kotlinx.coroutines.runBlocking` from `PreferenceSettingsStore.kt`.
4. Remove `buildSystemPromptBlocking()` from `SystemPromptBuilder.kt` (or keep with a clear
   comment that it is a convenience for non-coroutine callers).
5. Verify that `runBlocking` usage in `LiteRtRuntime.kt` (lines 443, 523), `ActionPlanningRuntime.kt`
   (line 145), and `ToolExecutor.kt` (lines 140, 197) are acceptable (they bridge to non-data-layer
   concerns: model inference and tool execution).
6. Update `LegacyPrefsMigrator.kt`: Remove `runBlocking` wrapper from `SolinAppContainer`; call
   `migrateIfNeeded()` from a coroutine scope during app initialization.
7. `SessionRepository.activeSessionId` property is fully removed; only `suspend fun activeSessionId()`
   remains.

**Deliverable**: No `*Blocking` methods remain. The data layer interfaces are 100% `suspend`.
`PreferenceSettingsStore` contains zero `runBlocking` calls. The only remaining `runBlocking`
usages are in non-data-layer code (model runtime, tool execution, action planning).

---

## 5. Risk Assessment Per Step

### Step 1 Risks (Low)

| Risk | Severity | Mitigation |
|------|----------|------------|
| Interface bloat (double the methods) | Low | Temporary; methods will be removed in Step 4 |
| Naming confusion (`Blocking` suffix) | Low | Establish clear convention: `fun foo()` = suspend, `fun fooBlocking()` = sync |
| Implementation errors in suspend wrappers | Medium | Write unit tests for each suspend method to verify parity with sync version |
| MemoryRepository `init` block race condition | Low | `init` does file cleanup + discovery; these are idempotent and safe to run async |

### Step 2 Risks (Medium)

| Risk | Severity | Mitigation |
|------|----------|------------|
| `ModelRepository.init` deferred initialization | **Medium** | Methods like `currentState()` may be called before init completes. Add a `Mutex` or `CompletableDeferred` for initialization readiness. Or make `discoverRecommendedDownloadedModels()` a `suspend` call inside `currentState()` with a `@Volatile var initialized` flag. |
| `SessionRepository.activeSessionId` property -> function | **High** | Property access `sessionRepository.activeSessionId` is used in 10+ call sites; changing to a function call requires all callers to be in suspend context. Keep the property as a `runBlocking` bridge until Step 3. |
| MemoryRepository state corruption from concurrent suspend calls | **Medium** | `MemoryRepository` uses a `linkedMapOf` (not thread-safe). Add `Mutex` protection or use `ConcurrentHashMap`. The `entries` map is accessed from `rebuild()`, `index()`, `search()`, `forget()`, and `reembedEntries()`. |
| Deadlocks from nested `runBlocking` in `*Blocking` methods | Low | Ensure `*Blocking` methods never call each other; they should only call their own suspend impl. Document this constraint. |

### Step 3 Risks (High)

| Risk | Severity | Mitigation |
|------|----------|------------|
| `createInitialState()` called during ViewModel construction | **High** | Changing to async initialization means the UI sees a loading state. Must ensure all UI components handle null/empty initial data gracefully. Add loading indicators. Test on cold start. |
| UI flicker on startup (loading state -> real state) | **Medium** | Use a "skeleton" loading state that looks similar to the real state, or keep the splash screen visible until initialization completes. |
| Call sites in `SolinViewModel` that are not in coroutines | **Medium** | Audit every call site. Methods like `updateModelState()` that call `modelRepository.currentState()` directly must be refactored. |
| `SolinAppContainer` lambda providers not suspend | **Medium** | The `actionModelPathProvider` lambda (line 444) is a method reference; if the interface expects a non-suspend lambda, the interface must change to accept `suspend () -> String?`. |
| Deprecation warnings in test code | Low | Tests may use sync methods; update tests to use `runTest { ... }` and suspend APIs. |
| `LegacyMemoryStorageRetirement.retireAfterSecureImport` called during AppContainer init | Medium | This calls `records.records()` and `records.upsert()` which become suspend. Either keep `runBlocking` for this migration path or launch in a background scope. |

### Step 4 Risks (Low)

| Risk | Severity | Mitigation |
|------|----------|------------|
| Hidden callers of removed methods | Low | Compiler will catch all references; grep for `Blocking` before removing |
| LegacyPrefsMigrator not migrated | **Medium** | Ensure `LegacyPrefsMigrator.migrate()` is called from a coroutine scope in `SolinAppContainer` |
| Third-party code calling sync APIs | Low | No external consumers of these interfaces; all callers are in the app module |

---

## 6. Testing Strategy Per Step

### Step 1 Testing

- **Unit tests**: For each interface+implementation, write paired tests that call both
  `foo()` and `fooBlocking()` and assert identical results.
- **Focus**: `PreferenceSettingsStore` -- verify suspend read/write produces same values as
  sync read/write for all key types (boolean, int, float, string).
- **Existing tests**: All existing tests must pass unchanged (they call the sync methods which
  still work).
- **Tool**: `runTest { ... }` from `kotlinx-coroutines-test` for suspend tests.

### Step 2 Testing

- **Concurrency tests**: Test `ModelRepository` initialization race: call `currentState()`
  immediately after construction while `init` is still running. Use a `CompletableDeferred`
  to gate initialization completion.
- **MemoryRepository thread safety**: Test concurrent `index()` + `search()` + `forget()` calls
  from multiple coroutines on `Dispatchers.Default`. Verify no `ConcurrentModificationException`.
- **SessionRepository**: Test that `activeSessionId()` resolves correctly on first call
  (lazy initialization). Test concurrent `createNewSession()` + `selectSession()` calls.
- **Integration**: Verify `MemoryController` works as `suspend` without manual `withContext` switching.
- **Run `./gradlew testDebugUnitTest`** after each implementation file change.

### Step 3 Testing

- **ViewModel initialization test**: Write a test that creates `SolinViewModel` and asserts
  that `uiState.value` first shows a loading state, then transitions to the real state within
  a timeout. Use `Turbine` for `StateFlow` testing.
- **UI test**: Espresso test that verifies the loading indicator appears and disappears.
  Verify that no `NullPointerException` occurs during the loading phase.
- **Deprecation audit**: `./gradlew compileDebugKotlin` and verify that only expected deprecation
  warnings appear (no new ones from missed call sites).
- **Full integration test**: Launch the app, create a session, send a message, switch models,
  verify memory works -- all core flows.
- **Cold start test**: Kill the app, relaunch, verify state is restored correctly.

### Step 4 Testing

- **Compile check**: `./gradlew assembleDebug` must succeed with zero errors and zero
  deprecation warnings from data layer.
- **Full test suite**: `./gradlew testDebugUnitTest connectedDebugAndroidTest` (or emulator
  equivalent).
- **Manual smoke test**: App launch, model download, chat session, memory indexing, settings
  changes, app restart and data persistence.
- **Lint check**: Verify no `runBlocking` in `PreferenceSettingsStore` or any data layer
  interface implementation.
- **Performance profile**: Use Android Studio Profiler to confirm no main-thread blocking
  during settings reads/writes, model selection, or session switching.

---

## 7. Estimated Effort Per Step (in Hours)

| Step | Hours | Notes |
|------|-------|-------|
| **Step 1: Add suspend variants** | 6-8 | ~170 methods across 13 interfaces + 17 implementations. Most are mechanical: add `suspend`, extract suspend impl, wrap sync impl in `runBlocking`. SettingsStore is the largest (18 methods). Memory stores add ~20 more. NoOp implementations are trivial. |
| **Step 2: Update internal callers** | 10-14 | ModelRepository internal refactor (4h), MemoryRepository suspend conversion + Mutex protection (4h), SessionRepository property->function bridge (2h), PreferenceSettingsStore runBlocking removal (1h), RemoteModelRepository + EncryptedSecretStore + HuggingFaceAuthRepository + others (1-2h). Plus concurrency safety review. |
| **Step 3: Deprecate sync, update ViewModel** | 12-16 | `createInitialState()` async initialization design + implementation (4h), auditing and migrating all ViewModel call sites (~60 call sites across 6500 lines) (5h), SolinAppContainer lambda provider changes + LegacyPrefsMigrator + LegacyMemoryStorageRetirement (2h), loading state UI handling (1-2h). |
| **Step 4: Remove sync variants** | 2-3 | Delete all `*Blocking` methods, remove imports, verify compilation. Low effort if Steps 1-3 were thorough. Compiler does most of the work. |
| **Testing (across all steps)** | 8-10 | Writing paired tests for Step 1 (2h), concurrency tests for Step 2 (3h), ViewModel initialization test for Step 3 (2h), full regression for Step 4 (1-2h). |
| **Total** | **38-51 hours** | Approximately 1-1.5 weeks of focused work for a single developer. Can be parallelized by interface area (e.g., one person does SettingsStore+repositories, another does Memory subsystem). |

---

## Appendix A: Recommended Naming Convention

Two viable naming schemes:

### Scheme A: `suspend` as default, `Blocking` for sync (RECOMMENDED)
```kotlin
interface GenerationParametersStore {
    suspend fun load(): GenerationParameters
    @Deprecated("Use suspend load()", level = WARNING)
    fun loadBlocking(): GenerationParameters
}
```
**Pros**: The "good" API has the clean name. The "bad" API has the ugly name, discouraging its use.
**Cons**: Requires changing all existing call sites in Step 3 (but compiler helps).

### Scheme B: `Async` suffix for suspend
```kotlin
interface GenerationParametersStore {
    fun load(): GenerationParameters  // stays as-is
    suspend fun loadAsync(): GenerationParameters  // new
}
```
**Pros**: No existing call sites need to change until Step 4.
**Cons**: The "good" API has an ugly suffix forever. Easy to forget to migrate.

**Recommendation**: Use Scheme A. The `Blocking` suffix clearly communicates the anti-pattern,
and having the clean name for the preferred API drives adoption.

---

## Appendix B: Key Architectural Decisions Required

### B.1 ViewModel Initialization

How should `createInitialState()` be made async?

- **Option 1 (RECOMMENDED)**: `MutableStateFlow` with loading state + `viewModelScope.launch`
  in `init {}` block. The UI shows a loading/skeleton state until data is ready.
  ```kotlin
  private val _uiState = MutableStateFlow(ChatUiState.initialLoadingState())
  init {
      viewModelScope.launch {
          _uiState.value = createInitialState()
          restoreStartupState(skipModelRuntimeWork)
      }
  }
  ```

- **Option 2**: Use `lateinit` and crash if accessed before ready. Not recommended for production.

- **Option 3**: Factory pattern where ViewModel is created only after data is loaded. Requires
  changes to `ViewModelProvider.Factory` and `SolinAppContainer.viewModelFactory()`. Adds
  complexity to the DI wiring.

### B.2 ModelRepository Initialization

The `init` block does file I/O + Room I/O. Options:

- **Option 1 (RECOMMENDED)**: Launch in `CoroutineScope(Dispatchers.IO)` from `init`. Methods
  that depend on initialization completion check a `@Volatile var initialized: Boolean` flag
  and call `discoverRecommendedDownloadedModels()` lazily if not yet done.

- **Option 2**: Expose `suspend fun initialize()` and require AppContainer to call it before
  use. More explicit but requires changes to all callers to handle the "not yet initialized" case.

- **Option 3**: Lazy initialization with `Mutex` per method. Each method checks if init is
  complete and runs it if needed. Thread-safe but adds complexity.

### B.3 SessionRepository.activeSessionId

Currently a `val` property initialized from `resolveActiveSessionId()` (which does Room +
DataStore I/O). Must become:

- **Option 1 (RECOMMENDED)**: `suspend fun activeSessionId(): String` with lazy caching.
  Consistent with all other methods. Requires all call sites to be in suspend context.

- **Option 2**: Cache eagerly in constructor via `runBlocking` (one-time cost, acceptable).
  The property remains sync but is initialized with a blocking call at construction time.
  Simpler for call sites but adds blocking I/O during ViewModel creation.

### B.4 MemoryRepository Thread Safety

`MemoryRepository` uses `linkedMapOf<String, MemoryEntry>()` (line 172) which is not thread-safe.
After migration to `suspend`, multiple coroutines may access `entries` concurrently.

- **Option 1 (RECOMMENDED)**: Add `private val mutex = Mutex()` and wrap all `entries` mutations
  with `mutex.withLock { ... }`. Read operations can use `mutex.withLock { entries.values.toList() }`.

- **Option 2**: Switch to `java.util.concurrent.ConcurrentHashMap`. Simpler but changes iteration
  semantics (no ordering guarantees).

### B.5 LegacyPrefsMigrator and LegacyMemoryStorageRetirement

Both are called during `SolinAppContainer` initialization and do data layer I/O.

- **Option 1 (RECOMMENDED)**: Keep `runBlocking` for these one-time startup operations.
  They run exactly once per app install (migration) or once per launch (legacy retirement). The blocking
  cost is paid during the splash screen and is invisible to the user.

- **Option 2**: Launch in a background scope and let the app start without the migrated data.
  More complex and may cause data inconsistency during the first few seconds.

---

## Appendix C: Files Requiring Changes

### Core Data Layer
1. `app/src/main/java/com/bytedance/zgx/solin/data/Stores.kt` -- interface definitions
2. `app/src/main/java/com/bytedance/zgx/solin/data/PreferenceSettingsStore.kt` -- main implementation
3. `app/src/main/java/com/bytedance/zgx/solin/data/SessionRepository.kt` -- SessionStore implementation
4. `app/src/main/java/com/bytedance/zgx/solin/data/ModelRepository.kt` -- ModelRepositoryFacade implementation
5. `app/src/main/java/com/bytedance/zgx/solin/data/RemoteModelRepository.kt` -- RemoteModelStore implementation
6. `app/src/main/java/com/bytedance/zgx/solin/data/GenerationParametersRepository.kt` -- GenerationParametersStore implementation
7. `app/src/main/java/com/bytedance/zgx/solin/data/FirstRunSetupRepository.kt` -- FirstRunSetupStore implementation
8. `app/src/main/java/com/bytedance/zgx/solin/data/EncryptedSecretStore.kt` -- SecretStore implementation
9. `app/src/main/java/com/bytedance/zgx/solin/data/HuggingFaceAuthRepository.kt` -- HuggingFaceAuthStore interface + implementation
10. `app/src/main/java/com/bytedance/zgx/solin/data/LegacyPrefsMigrator.kt` -- migration code using settingsStore/secretStore

### Memory Subsystem
11. `app/src/main/java/com/bytedance/zgx/solin/memory/MemoryRepository.kt` -- MemoryIndex, LongTermMemoryControls, SemanticMemoryRuntimeController interfaces + `MemoryRepository`; `memory/MemoryStores.kt` -- MemoryRecordStore, MemoryEmbeddingStore, MemoryDeletionEventStore interfaces + NoOp implementations; `memory/RoomMemoryStores.kt` -- Room implementations

### Consumers
12. `app/src/main/java/com/bytedance/zgx/solin/SolinViewModel.kt` -- primary consumer (~60 call sites)
13. `app/src/main/java/com/bytedance/zgx/solin/memory/MemoryController.kt` -- synchronous today; methods become suspend
14. `app/src/main/java/com/bytedance/zgx/solin/SolinAppContainer.kt` -- wiring + lambda providers
15. `app/src/main/java/com/bytedance/zgx/solin/orchestration/SystemPromptBuilder.kt` -- already has suspend + blocking pattern

### Room DAOs (if not already suspend)
16. `app/src/main/java/com/bytedance/zgx/solin/data/SolinDatabaseDaos.kt` -- SessionDao, ModelDao, DownloadRecordDao, MemoryRecordDao, MemoryEmbeddingDao, MemoryDeletionEventDao, MemoryDeletionTransactionDao interfaces
