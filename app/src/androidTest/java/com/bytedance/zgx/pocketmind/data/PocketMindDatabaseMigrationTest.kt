package com.bytedance.zgx.pocketmind.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.memory.MemoryRecordSensitivity
import com.bytedance.zgx.pocketmind.memory.MemoryRecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketMindDatabaseMigrationTest {
    @Test
    fun migration3ToCurrentAddsPrivacyDefaultAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion3Schema(db)
            db.execSQL(
                """
                INSERT INTO chat_sessions(id, title, createdAtMillis, updatedAtMillis)
                VALUES('session-1', '旧会话', 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chat_messages(sessionId, position, role, text, tokenCount, tokensPerSecond)
                VALUES('session-1', 0, 'User', '分享文本：secret', NULL, NULL)
                """.trimIndent(),
            )
            db.version = 3
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_3_4,
                PocketMindDatabase.MIGRATION_4_5,
                PocketMindDatabase.MIGRATION_5_6,
                PocketMindDatabase.MIGRATION_6_7,
                PocketMindDatabase.MIGRATION_7_8,
                PocketMindDatabase.MIGRATION_8_9,
                PocketMindDatabase.MIGRATION_9_10,
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val restored = database.sessionDao().messagesForSession("session-1").single()
            assertEquals(MessagePrivacy.LocalOnly.name, restored.privacy)
            assertTrue(database.chatMessagesPrivacyColumnDefaultsTo(MessagePrivacy.LocalOnly.name))
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun legacyPrefsMigratorImportsLegacyMessagesAsLocalOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pocketmind", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString(
                "sessions_json",
                """
                [
                  {
                    "id": "legacy-session",
                    "title": "旧会话",
                    "createdAtMillis": 1,
                    "updatedAtMillis": 2,
                    "messages": [
                      {"role": "User", "text": "分享文本：secret"}
                    ]
                  }
                ]
                """.trimIndent(),
            )
            .putString("active_session_id", "legacy-session")
            .apply()
        val database = Room.inMemoryDatabaseBuilder(context, PocketMindDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settingsStore = PreferenceSettingsStore(context)
        settingsStore.setMigrationVersion(0)

        try {
            LegacyPrefsMigrator(
                context = context,
                database = database,
                settingsStore = settingsStore,
                secretStore = NoOpSecretStore,
            ).migrateIfNeeded()

            val restored = database.sessionDao().messagesForSession("legacy-session").single()
            assertEquals("分享文本：secret", restored.text)
            assertEquals(MessagePrivacy.LocalOnly.name, restored.privacy)
        } finally {
            database.close()
            prefs.edit().clear().apply()
            settingsStore.setMigrationVersion(0)
        }
    }

    @Test
    fun legacyPrefsMigratorDerivesUntitledLegacyMessagesAsLocalOnlyTitle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pocketmind", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString(
                "sessions_json",
                """
                [
                  {
                    "id": "legacy-private-title-session",
                    "createdAtMillis": 1,
                    "updatedAtMillis": 2,
                    "messages": [
                      {"role": "User", "text": "分享文本：secret-title-token"}
                    ]
                  }
                ]
                """.trimIndent(),
            )
            .putString("active_session_id", "legacy-private-title-session")
            .apply()
        val database = Room.inMemoryDatabaseBuilder(context, PocketMindDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settingsStore = PreferenceSettingsStore(context)
        settingsStore.setMigrationVersion(0)

        try {
            LegacyPrefsMigrator(
                context = context,
                database = database,
                settingsStore = settingsStore,
                secretStore = NoOpSecretStore,
            ).migrateIfNeeded()

            val session = database.sessionDao().session("legacy-private-title-session")
            val restored = database.sessionDao().messagesForSession("legacy-private-title-session").single()
            assertEquals("本地内容", session?.title)
            assertFalse(session?.title.orEmpty().contains("secret-title-token"))
            assertEquals(MessagePrivacy.LocalOnly.name, restored.privacy)
        } finally {
            database.close()
            prefs.edit().clear().apply()
            settingsStore.setMigrationVersion(0)
        }
    }

    @Test
    fun migration4To5CreatesMemoryRecordsTableAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion4Schema(db)
            db.version = 4
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_4_5,
                PocketMindDatabase.MIGRATION_5_6,
                PocketMindDatabase.MIGRATION_6_7,
                PocketMindDatabase.MIGRATION_7_8,
                PocketMindDatabase.MIGRATION_8_9,
                PocketMindDatabase.MIGRATION_9_10,
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            database.memoryRecordDao().upsert(
                MemoryRecordEntity(
                    id = "pref-1",
                    type = "Preference",
                    text = "用户偏好：简洁回答",
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
            assertEquals("用户偏好：简洁回答", database.memoryRecordDao().record("pref-1")?.text)
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration5To6CreatesAgentTraceTablesAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion5Schema(db)
            db.version = 5
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_5_6,
                PocketMindDatabase.MIGRATION_6_7,
                PocketMindDatabase.MIGRATION_7_8,
                PocketMindDatabase.MIGRATION_8_9,
                PocketMindDatabase.MIGRATION_9_10,
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            database.agentTraceDao().upsertRun(
                AgentRunEntity(
                    id = "run-1",
                    input = "hello",
                    state = "Created",
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
            database.agentTraceDao().insertStep(
                AgentStepEntity(
                    runId = "run-1",
                    position = 0,
                    type = "AssistantResponded",
                    summary = "Assistant responded.",
                    json = """{"type":"AssistantResponded"}""",
                    createdAtMillis = 2L,
                ),
            )

            assertEquals("Created", database.agentTraceDao().run("run-1")?.state)
            assertEquals("AssistantResponded", database.agentTraceDao().steps("run-1").single().type)
            assertTrue(database.agentTraceTablesExist())
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration6To8CreatesPendingAgentConfirmationsTableAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion6Schema(db)
            db.version = 6
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_6_7,
                PocketMindDatabase.MIGRATION_7_8,
                PocketMindDatabase.MIGRATION_8_9,
                PocketMindDatabase.MIGRATION_9_10,
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            database.agentTraceDao().upsertRun(
                AgentRunEntity(
                    id = "run-1",
                    input = "open wifi",
                    state = "AwaitingUserConfirmation",
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
            database.agentTraceDao().upsertPendingConfirmation(
                PendingAgentConfirmationEntity(
                    runId = "run-1",
                    requestId = "request-1",
                    toolName = "open_wifi_settings",
                    argumentsJson = "{}",
                    reason = "Open Wi-Fi",
                    draftFunctionName = "open_wifi_settings",
                    draftTitle = "Wi-Fi",
                    draftSummary = "Open Wi-Fi",
                    draftParametersJson = "{}",
                    skillId = null,
                    skillPlanJson = null,
                    plannedByModel = false,
                    fallbackReason = null,
                    nextActionInput = "打开 Wi-Fi 设置",
                    createdAtMillis = 2L,
                    updatedAtMillis = 2L,
                ),
            )

            val pending = database.agentTraceDao().latestPendingConfirmation()
            assertEquals("request-1", pending?.requestId)
            assertEquals("打开 Wi-Fi 设置", pending?.nextActionInput)
            assertTrue(database.pendingAgentConfirmationsTableExists())
            assertTrue(database.pendingNextActionInputColumnIsNullable())
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration7To8AddsPendingNextActionInputColumnAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion7Schema(db)
            db.execSQL(
                """
                INSERT INTO agent_runs(id, input, state, createdAtMillis, updatedAtMillis)
                VALUES('run-1', '[redacted]', 'AwaitingUserConfirmation', 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO pending_agent_confirmations(
                    runId,
                    requestId,
                    toolName,
                    argumentsJson,
                    reason,
                    draftFunctionName,
                    draftTitle,
                    draftSummary,
                    draftParametersJson,
                    skillId,
                    skillPlanJson,
                    plannedByModel,
                    fallbackReason,
                    createdAtMillis,
                    updatedAtMillis
                ) VALUES(
                    'run-1',
                    'request-1',
                    'open_wifi_settings',
                    '{}',
                    'Open Wi-Fi',
                    'open_wifi_settings',
                    'Wi-Fi',
                    'Open Wi-Fi',
                    '{}',
                    NULL,
                    NULL,
                    0,
                    NULL,
                    2,
                    2
                )
                """.trimIndent(),
            )
            db.version = 7
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_7_8,
                PocketMindDatabase.MIGRATION_8_9,
                PocketMindDatabase.MIGRATION_9_10,
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val pending = database.agentTraceDao().latestPendingConfirmation()
            assertEquals("request-1", pending?.requestId)
            assertEquals(null, pending?.nextActionInput)
            assertTrue(database.pendingNextActionInputColumnIsNullable())
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration8To9CreatesSkillRunCheckpointsTableAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion8Schema(db)
            db.version = 8
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_8_9,
                PocketMindDatabase.MIGRATION_9_10,
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            database.agentTraceDao().upsertSkillRunCheckpoint(
                AgentSkillRunCheckpointEntity(
                    runId = "run-1",
                    requestId = "request-1",
                    skillId = "skill-1",
                    skillRequestId = "skill-request-1",
                    manifestId = "skill-1",
                    manifestVersion = 1,
                    manifestHash = "a".repeat(64),
                    phase = "AwaitingToolConfirmation",
                    pendingStepIndex = 0,
                    pendingStepId = "tool:request-1",
                    pendingToolName = "read_clipboard",
                    completedStepIdsJson = "[]",
                    outputKeysByStepJson = "{}",
                    privateOutputRefsJson = "[]",
                    schemaVersion = 1,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
            val checkpoint = database.agentTraceDao().skillRunCheckpoint("run-1", "request-1")
            assertEquals("skill-1", checkpoint?.skillId)
            assertTrue(database.skillRunCheckpointsTableExists())
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration9To10AddsAgentRunSessionIdColumnAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion9Schema(db)
            db.execSQL(
                """
                INSERT INTO agent_runs(id, input, state, createdAtMillis, updatedAtMillis)
                VALUES('legacy-run', '[redacted]', 'AwaitingUserConfirmation', 1, 1)
                """.trimIndent(),
            )
            db.version = 9
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_9_10,
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val restored = database.agentTraceDao().run("legacy-run")
            assertEquals(null, restored?.sessionId)
            assertTrue(database.agentRunSessionIdColumnIsNullable())
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration10To11AddsContinuationCursorColumnAndClearsRawNextActionInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion10Schema(db)
            db.execSQL(
                """
                INSERT INTO agent_runs(id, input, state, createdAtMillis, updatedAtMillis, sessionId)
                VALUES('run-legacy-cursor', '[redacted]', 'AwaitingUserConfirmation', 1, 1, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO pending_agent_confirmations(
                    runId,
                    requestId,
                    toolName,
                    argumentsJson,
                    reason,
                    draftFunctionName,
                    draftTitle,
                    draftSummary,
                    draftParametersJson,
                    skillId,
                    skillPlanJson,
                    plannedByModel,
                    fallbackReason,
                    nextActionInput,
                    createdAtMillis,
                    updatedAtMillis
                )
                VALUES(
                    'run-legacy-cursor',
                    'request-wifi',
                    'open_wifi_settings',
                    '{}',
                    '[redacted]',
                    'open_wifi_settings',
                    '[redacted]',
                    '[redacted]',
                    '{}',
                    NULL,
                    NULL,
                    0,
                    NULL,
                    'legacy raw sequence tail',
                    1,
                    1
                )
                """.trimIndent(),
            )
            db.version = 10
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_10_11,
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val pending = database.agentTraceDao().latestPendingConfirmation()
            assertEquals(null, pending?.nextActionInput)
            assertEquals(null, pending?.continuationCursorJson)
            assertTrue(database.pendingContinuationCursorColumnIsNullable())
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration11To12ChangesChatMessagePrivacyDefaultToLocalOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion11Schema(db)
            db.execSQL(
                """
                INSERT INTO chat_messages(sessionId, position, role, text, tokenCount, tokensPerSecond, privacy)
                VALUES('session-remote', 0, 'User', '用户明确远端消息', NULL, NULL, '${MessagePrivacy.RemoteEligible.name}')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chat_messages(sessionId, position, role, text, tokenCount, tokensPerSecond, privacy)
                VALUES('session-local', 0, 'User', '本地消息', NULL, NULL, '${MessagePrivacy.LocalOnly.name}')
                """.trimIndent(),
            )
            db.version = 11
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_11_12,
                PocketMindDatabase.MIGRATION_12_13,
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            assertTrue(database.chatMessagesPrivacyColumnDefaultsTo(MessagePrivacy.LocalOnly.name))
            assertEquals(
                MessagePrivacy.RemoteEligible.name,
                database.sessionDao().messagesForSession("session-remote").single().privacy,
            )
            assertEquals(
                MessagePrivacy.LocalOnly.name,
                database.sessionDao().messagesForSession("session-local").single().privacy,
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO chat_messages(sessionId, position, role, text, tokenCount, tokensPerSecond)
                VALUES('session-default', 0, 'User', '省略 privacy 的新消息', NULL, NULL)
                """.trimIndent(),
            )
            assertEquals(
                MessagePrivacy.LocalOnly.name,
                database.sessionDao().messagesForSession("session-default").single().privacy,
            )
            assertTrue(database.remoteSendAuditTableExists())
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration13To14AddsMemoryEmbeddingsTable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion13Schema(db)
            db.version = 13
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_13_14,
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            assertTrue(database.memoryEmbeddingsTableExists())
            assertTrue(database.memoryEmbeddingsHasExpectedColumns())
            assertTrue(database.memoryRecordsMetadataHasExpectedColumns())
            database.memoryRecordDao().upsert(
                MemoryRecordEntity(
                    id = "pref-1",
                    type = "Preference",
                    text = "用户偏好：回复简洁",
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
            )
            database.memoryEmbeddingDao().upsert(
                MemoryEmbeddingEntity(
                    recordId = "pref-1",
                    modelId = "memory-embedding-300m",
                    sourceHash = "hash-1",
                    dimension = 2,
                    vectorBlob = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
                    updatedAtMillis = 2L,
                ),
            )

            val restored = database.memoryEmbeddingDao().embedding("pref-1", "memory-embedding-300m")
            assertEquals("hash-1", restored?.sourceHash)
            assertEquals(2, restored?.dimension)

            database.memoryRecordDao().delete("pref-1")

            assertNull(database.memoryEmbeddingDao().embedding("pref-1", "memory-embedding-300m"))
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration14To15AddsMemoryRecordMetadataDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion14Schema(db)
            db.execSQL(
                """
                INSERT INTO memory_records(id, type, text, createdAtMillis, updatedAtMillis)
                VALUES('pref-legacy', 'Preference', '用户偏好：简洁回答', 1, 1)
                """.trimIndent(),
            )
            db.version = 14
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(
                PocketMindDatabase.MIGRATION_14_15,
                PocketMindDatabase.MIGRATION_15_16,
                PocketMindDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()

        try {
            assertTrue(database.memoryRecordsMetadataHasExpectedColumns())
            val record = database.memoryRecordDao().record("pref-legacy")
            assertEquals(MemoryRecordSource.LegacyImport.name, record?.source)
            assertEquals(MemoryRecordSensitivity.Normal.name, record?.sensitivity)
            assertEquals(MessagePrivacy.LocalOnly.name, record?.privacy)
            assertNull(record?.expiresAtMillis)
            assertNull(record?.conflictKey)
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration15To16AddsMemoryDeletionEventsTableAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion15Schema(db)
            db.version = 15
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(PocketMindDatabase.MIGRATION_15_16, PocketMindDatabase.MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()

        try {
            assertTrue(database.memoryDeletionEventsTableExists())
            assertTrue(database.memoryDeletionEventsHasExpectedColumns())
            database.memoryDeletionEventDao().insert(
                MemoryDeletionEventEntity(
                    id = "deletion-1",
                    recordId = "pref-1",
                    recordType = "Preference",
                    operation = "Forget",
                    recordTextHash = "hash-only",
                    recordSource = MemoryRecordSource.ExplicitUser.name,
                    recordSensitivity = MemoryRecordSensitivity.Normal.name,
                    conflictKey = "response-length",
                    deletedAtMillis = 42L,
                ),
            )

            val restored = database.memoryDeletionEventDao().events().single()
            assertEquals("pref-1", restored.recordId)
            assertEquals("hash-only", restored.recordTextHash)
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    @Test
    fun migration16To17AddsLocalStorageMigrationStateAndRoomCanOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion16Schema(db)
            db.version = 16
        }

        val database = Room.databaseBuilder(
            context,
            PocketMindDatabase::class.java,
            TEST_DB_NAME,
        )
            .addMigrations(PocketMindDatabase.MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()

        try {
            assertTrue(database.localStorageMigrationStateTableExists())
            assertTrue(database.localStorageMigrationStateHasExpectedColumns())
            database.localStorageMigrationStateDao().upsert(
                LocalStorageMigrationStateEntity(
                    id = "zvec-v1",
                    phase = "memory",
                    lastDomain = "memory",
                    lastId = "pref-1",
                    startedAtMillis = 1L,
                    completedAtMillis = null,
                    errorJson = null,
                    schemaVersion = 1,
                ),
            )

            val restored = database.localStorageMigrationStateDao().state("zvec-v1")
            assertEquals("pref-1", restored?.lastId)
        } finally {
            database.close()
            context.deleteDatabase(TEST_DB_NAME)
        }
    }

    private fun PocketMindDatabase.chatMessagesPrivacyColumnDefaultsTo(expectedDefault: String): Boolean {
        openHelper.writableDatabase.query("PRAGMA table_info(`chat_messages`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name != "privacy") continue
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                val defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                return notNull == 1 && defaultValue == "'$expectedDefault'"
            }
        }
        return false
    }

    private fun PocketMindDatabase.remoteSendAuditTableExists(): Boolean {
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'remote_send_audit_events'",
        ).use { cursor ->
            return cursor.moveToNext()
        }
    }

    private fun PocketMindDatabase.memoryEmbeddingsTableExists(): Boolean {
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'memory_embeddings'",
        ).use { cursor ->
            return cursor.moveToNext()
        }
    }

    private fun PocketMindDatabase.memoryEmbeddingsHasExpectedColumns(): Boolean {
        val columns = mutableSetOf<String>()
        openHelper.writableDatabase.query("PRAGMA table_info(`memory_embeddings`)").use { cursor ->
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return columns == setOf(
            "recordId",
            "modelId",
            "sourceHash",
            "dimension",
            "vectorBlob",
            "updatedAtMillis",
        )
    }

    private fun PocketMindDatabase.memoryRecordsMetadataHasExpectedColumns(): Boolean {
        val columns = mutableSetOf<String>()
        openHelper.writableDatabase.query("PRAGMA table_info(`memory_records`)").use { cursor ->
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return setOf(
            "source",
            "sensitivity",
            "privacy",
            "expiresAtMillis",
            "conflictKey",
        ).all { column -> column in columns }
    }

    private fun PocketMindDatabase.memoryDeletionEventsTableExists(): Boolean {
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'memory_deletion_events'",
        ).use { cursor ->
            return cursor.moveToNext()
        }
    }

    private fun PocketMindDatabase.memoryDeletionEventsHasExpectedColumns(): Boolean {
        val columns = mutableSetOf<String>()
        openHelper.writableDatabase.query("PRAGMA table_info(`memory_deletion_events`)").use { cursor ->
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return columns == setOf(
            "id",
            "recordId",
            "recordType",
            "operation",
            "recordTextHash",
            "recordSource",
            "recordSensitivity",
            "conflictKey",
            "deletedAtMillis",
        )
    }

    private fun PocketMindDatabase.localStorageMigrationStateTableExists(): Boolean {
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'local_storage_migration_state'",
        ).use { cursor ->
            return cursor.moveToNext()
        }
    }

    private fun PocketMindDatabase.localStorageMigrationStateHasExpectedColumns(): Boolean {
        val columns = mutableSetOf<String>()
        openHelper.writableDatabase.query("PRAGMA table_info(`local_storage_migration_state`)").use { cursor ->
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return columns == setOf(
            "id",
            "phase",
            "lastDomain",
            "lastId",
            "startedAtMillis",
            "completedAtMillis",
            "errorJson",
            "schemaVersion",
        )
    }

    private fun createVersion3Schema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `sessionId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `role` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `tokenCount` INTEGER,
                `tokensPerSecond` REAL,
                PRIMARY KEY(`sessionId`, `position`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `installed_models` (
                `id` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `path` TEXT NOT NULL,
                `fileBytes` INTEGER NOT NULL,
                `recommendedModelId` TEXT,
                `sourceRevision` TEXT,
                `verifiedSha256` TEXT,
                `verificationStatus` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_records` (
                `id` TEXT NOT NULL,
                `downloadManagerId` INTEGER NOT NULL,
                `sourceJson` TEXT NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tool_audit_events` (
                `id` TEXT NOT NULL,
                `runId` TEXT,
                `requestId` TEXT,
                `toolName` TEXT,
                `skillId` TEXT,
                `eventType` TEXT NOT NULL,
                `status` TEXT,
                `riskLevel` TEXT,
                `permissionsCsv` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scheduled_tasks` (
                `id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `body` TEXT NOT NULL,
                `triggerAtMillis` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }

    private fun createVersion4Schema(db: SQLiteDatabase) {
        createVersion3Schema(db)
        db.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `privacy` TEXT NOT NULL DEFAULT '${MessagePrivacy.RemoteEligible.name}'",
        )
    }

    private fun createVersion5Schema(db: SQLiteDatabase) {
        createVersion4Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_records` (
                `id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }

    private fun createVersion6Schema(db: SQLiteDatabase) {
        createVersion5Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_runs` (
                `id` TEXT NOT NULL,
                `input` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_steps` (
                `runId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `json` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`runId`, `position`)
            )
            """.trimIndent(),
        )
    }

    private fun createVersion7Schema(db: SQLiteDatabase) {
        createVersion6Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_agent_confirmations` (
                `runId` TEXT NOT NULL,
                `requestId` TEXT NOT NULL,
                `toolName` TEXT NOT NULL,
                `argumentsJson` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `draftFunctionName` TEXT NOT NULL,
                `draftTitle` TEXT NOT NULL,
                `draftSummary` TEXT NOT NULL,
                `draftParametersJson` TEXT NOT NULL,
                `skillId` TEXT,
                `skillPlanJson` TEXT,
                `plannedByModel` INTEGER NOT NULL,
                `fallbackReason` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`runId`)
            )
            """.trimIndent(),
        )
    }

    private fun createVersion8Schema(db: SQLiteDatabase) {
        createVersion7Schema(db)
        db.execSQL(
            "ALTER TABLE `pending_agent_confirmations` ADD COLUMN `nextActionInput` TEXT",
        )
    }

    private fun createVersion9Schema(db: SQLiteDatabase) {
        createVersion8Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_skill_run_checkpoints` (
                `runId` TEXT NOT NULL,
                `requestId` TEXT NOT NULL,
                `skillId` TEXT NOT NULL,
                `skillRequestId` TEXT NOT NULL,
                `manifestId` TEXT NOT NULL,
                `manifestVersion` INTEGER NOT NULL,
                `manifestHash` TEXT NOT NULL,
                `phase` TEXT NOT NULL,
                `pendingStepIndex` INTEGER NOT NULL,
                `pendingStepId` TEXT NOT NULL,
                `pendingToolName` TEXT NOT NULL,
                `completedStepIdsJson` TEXT NOT NULL,
                `outputKeysByStepJson` TEXT NOT NULL,
                `privateOutputRefsJson` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`runId`, `requestId`)
            )
            """.trimIndent(),
        )
    }

    private fun createVersion10Schema(db: SQLiteDatabase) {
        createVersion9Schema(db)
        db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `sessionId` TEXT")
    }

    private fun createVersion11Schema(db: SQLiteDatabase) {
        createVersion10Schema(db)
        db.execSQL(
            "ALTER TABLE `pending_agent_confirmations` ADD COLUMN `continuationCursorJson` TEXT",
        )
    }

    private fun createVersion12Schema(db: SQLiteDatabase) {
        createVersion11Schema(db)
        db.execSQL("ALTER TABLE `chat_messages` RENAME TO `chat_messages_legacy_default`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `sessionId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `role` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `tokenCount` INTEGER,
                `tokensPerSecond` REAL,
                `privacy` TEXT NOT NULL DEFAULT '${MessagePrivacy.LocalOnly.name}',
                PRIMARY KEY(`sessionId`, `position`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `chat_messages` (
                `sessionId`,
                `position`,
                `role`,
                `text`,
                `tokenCount`,
                `tokensPerSecond`,
                `privacy`
            )
            SELECT
                `sessionId`,
                `position`,
                `role`,
                `text`,
                `tokenCount`,
                `tokensPerSecond`,
                `privacy`
            FROM `chat_messages_legacy_default`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `chat_messages_legacy_default`")
    }

    private fun createVersion13Schema(db: SQLiteDatabase) {
        createVersion12Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `remote_send_audit_events` (
                `id` TEXT NOT NULL,
                `decision` TEXT NOT NULL,
                `modelName` TEXT,
                `sensitiveCategoriesCsv` TEXT NOT NULL,
                `imageCount` INTEGER NOT NULL,
                `remoteHistoryCount` INTEGER NOT NULL,
                `summary` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }

    private fun createVersion14Schema(db: SQLiteDatabase) {
        createVersion13Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_embeddings` (
                `recordId` TEXT NOT NULL,
                `modelId` TEXT NOT NULL,
                `sourceHash` TEXT NOT NULL,
                `dimension` INTEGER NOT NULL,
                `vectorBlob` BLOB NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`recordId`, `modelId`),
                FOREIGN KEY(`recordId`) REFERENCES `memory_records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_recordId` ON `memory_embeddings` (`recordId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_modelId` ON `memory_embeddings` (`modelId`)")
    }

    private fun createVersion15Schema(db: SQLiteDatabase) {
        createVersion14Schema(db)
        db.execSQL(
            "ALTER TABLE `memory_records` ADD COLUMN `source` TEXT NOT NULL DEFAULT '${MemoryRecordSource.LegacyImport.name}'",
        )
        db.execSQL(
            "ALTER TABLE `memory_records` ADD COLUMN `sensitivity` TEXT NOT NULL DEFAULT '${MemoryRecordSensitivity.Normal.name}'",
        )
        db.execSQL(
            "ALTER TABLE `memory_records` ADD COLUMN `privacy` TEXT NOT NULL DEFAULT '${MessagePrivacy.LocalOnly.name}'",
        )
        db.execSQL("ALTER TABLE `memory_records` ADD COLUMN `expiresAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `memory_records` ADD COLUMN `conflictKey` TEXT")
    }

    private fun createVersion16Schema(db: SQLiteDatabase) {
        createVersion15Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_deletion_events` (
                `id` TEXT NOT NULL,
                `recordId` TEXT NOT NULL,
                `recordType` TEXT NOT NULL,
                `operation` TEXT NOT NULL,
                `recordTextHash` TEXT NOT NULL,
                `recordSource` TEXT NOT NULL,
                `recordSensitivity` TEXT NOT NULL,
                `conflictKey` TEXT,
                `deletedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }

    private fun PocketMindDatabase.agentTraceTablesExist(): Boolean {
        val tables = mutableSetOf<String>()
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('agent_runs', 'agent_steps')",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return tables == setOf("agent_runs", "agent_steps")
    }

    private fun PocketMindDatabase.pendingAgentConfirmationsTableExists(): Boolean {
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'pending_agent_confirmations'",
        ).use { cursor ->
            return cursor.moveToNext()
        }
    }

    private fun PocketMindDatabase.pendingNextActionInputColumnIsNullable(): Boolean {
        openHelper.writableDatabase.query("PRAGMA table_info(`pending_agent_confirmations`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name != "nextActionInput") continue
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                return notNull == 0
            }
        }
        return false
    }

    private fun PocketMindDatabase.pendingContinuationCursorColumnIsNullable(): Boolean {
        openHelper.writableDatabase.query("PRAGMA table_info(`pending_agent_confirmations`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name != "continuationCursorJson") continue
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                return notNull == 0
            }
        }
        return false
    }

    private fun PocketMindDatabase.skillRunCheckpointsTableExists(): Boolean {
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_skill_run_checkpoints'",
        ).use { cursor ->
            return cursor.moveToNext()
        }
    }

    private fun PocketMindDatabase.agentRunSessionIdColumnIsNullable(): Boolean {
        openHelper.writableDatabase.query("PRAGMA table_info(`agent_runs`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name != "sessionId") continue
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                return notNull == 0
            }
        }
        return false
    }

    private object NoOpSecretStore : SecretStore {
        override fun loadString(name: String): Result<String> =
            Result.success("")

        override fun saveString(name: String, value: String): Result<Unit> =
            Result.success(Unit)
    }

    private companion object {
        const val TEST_DB_NAME = "pocketmind-migration-test.db"
    }
}
