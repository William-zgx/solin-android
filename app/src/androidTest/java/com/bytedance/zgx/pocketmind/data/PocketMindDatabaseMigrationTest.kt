package com.bytedance.zgx.pocketmind.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.pocketmind.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketMindDatabaseMigrationTest {
    @Test
    fun migration3To4AddsPrivacyDefaultAndRoomCanOpen() {
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
            )
            .allowMainThreadQueries()
            .build()

        try {
            val restored = database.sessionDao().messagesForSession("session-1").single()
            assertEquals(MessagePrivacy.LocalOnly.name, restored.privacy)
            assertTrue(database.chatMessagesPrivacyColumnHasDefault())
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
            .addMigrations(PocketMindDatabase.MIGRATION_7_8, PocketMindDatabase.MIGRATION_8_9)
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
            .addMigrations(PocketMindDatabase.MIGRATION_8_9)
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

    private fun PocketMindDatabase.chatMessagesPrivacyColumnHasDefault(): Boolean {
        openHelper.writableDatabase.query("PRAGMA table_info(`chat_messages`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name != "privacy") continue
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                val defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                return notNull == 1 && defaultValue == "'${MessagePrivacy.RemoteEligible.name}'"
            }
        }
        return false
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

    private fun PocketMindDatabase.skillRunCheckpointsTableExists(): Boolean {
        openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_skill_run_checkpoints'",
        ).use { cursor ->
            return cursor.moveToNext()
        }
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
