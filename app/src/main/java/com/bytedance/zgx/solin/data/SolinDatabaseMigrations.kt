package com.bytedance.zgx.solin.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.memory.MemoryRecordSensitivity
import com.bytedance.zgx.solin.memory.MemoryRecordSource

/*
 * Schema migrations of [SolinDatabase]. Split out of SolinDatabase.kt; migration SQL unchanged.
 * Never edit a shipped migration: add a new MIGRATION_N_N+1 and append it to [ALL_MIGRATIONS].
 */

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `privacy` TEXT NOT NULL DEFAULT '${MessagePrivacy.RemoteEligible.name}'",
        )
        db.execSQL(
            "UPDATE `chat_messages` SET `privacy` = '${MessagePrivacy.LocalOnly.name}'",
        )
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `pending_agent_confirmations` ADD COLUMN `nextActionInput` TEXT",
        )
    }
}

internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `sessionId` TEXT")
    }
}

internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `pending_agent_confirmations` ADD COLUMN `continuationCursorJson` TEXT",
        )
        db.execSQL("UPDATE `pending_agent_confirmations` SET `nextActionInput` = NULL")
    }
}

internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

internal val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_storage_migration_state` (
                `id` TEXT NOT NULL,
                `phase` TEXT NOT NULL,
                `lastDomain` TEXT,
                `lastId` TEXT,
                `startedAtMillis` INTEGER NOT NULL,
                `completedAtMillis` INTEGER,
                `errorJson` TEXT,
                `schemaVersion` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_run_placement_bindings` (
                `runId` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `policyVersion` INTEGER NOT NULL,
                `preference` TEXT NOT NULL,
                `placement` TEXT NOT NULL,
                `primaryReason` TEXT NOT NULL,
                `complexity` TEXT NOT NULL,
                `resourceBand` TEXT NOT NULL,
                `localState` TEXT NOT NULL,
                `remoteState` TEXT NOT NULL,
                `remoteProfileRevision` TEXT,
                `bootCount` INTEGER NOT NULL,
                `boundAtElapsedRealtimeMillis` INTEGER NOT NULL,
                `dispatchState` TEXT NOT NULL,
                `attempt` INTEGER NOT NULL,
                PRIMARY KEY(`runId`),
                FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

/**
 * Every schema migration in version order. The single source of truth for both
 * [SolinDatabase] and the instrumented migration tests, so a new migration cannot be wired into
 * one and forgotten in the other.
 */
internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18,
)

/** The contiguous migration chain from [startVersion] up to the current schema version. */
internal fun migrationsFrom(startVersion: Int): Array<Migration> =
    ALL_MIGRATIONS.filter { migration -> migration.startVersion >= startVersion }.toTypedArray()
