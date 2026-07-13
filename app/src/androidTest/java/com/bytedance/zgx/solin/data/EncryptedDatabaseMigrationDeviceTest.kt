package com.bytedance.zgx.solin.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bytedance.zgx.solin.MessagePrivacy
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EncryptedDatabaseMigrationDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile: File
        get() = context.getDatabasePath("solin.db")

    @Before
    fun setUp() {
        SolinDatabase.closeForInstrumentation()
        deleteDatabaseArtifacts(databaseFile)
        context.noBackupFilesDir.resolve("solin-crypto").deleteRecursively()
    }

    @After
    fun tearDown() {
        SolinDatabase.closeForInstrumentation()
        deleteDatabaseArtifacts(databaseFile)
        context.noBackupFilesDir.resolve("solin-crypto").deleteRecursively()
    }

    @Test
    fun plaintextDatabaseIsPhysicallyMigratedToSqlCipher() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            createVersion3Schema(database)
            seedVersion3Database(database)
        }

        val database = SolinDatabase.get(context)
        try {
            assertVersion3DataWasMigrated(database)
        } finally {
            SolinDatabase.closeForInstrumentation()
        }
    }

    @Test
    fun plaintextWalCommittedDataIsMigratedToSqlCipher() {
        val databaseSnapshot = File(context.cacheDir, "solin-wal-snapshot.db")
        val walSnapshot = File(context.cacheDir, "solin-wal-snapshot.db-wal")
        deleteDatabaseArtifacts(databaseSnapshot)
        val plaintextDatabase = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            createVersion3Schema(plaintextDatabase)
            assertTrue(plaintextDatabase.enableWriteAheadLogging())
            seedVersion3Database(plaintextDatabase)
            plaintextDatabase.execSQL(
                "INSERT INTO chat_messages VALUES ('session-1', 1, 'Assistant', 'wal canary', NULL, NULL)",
            )
            val walFile = File("${databaseFile.absolutePath}-wal")
            assertTrue(walFile.length() > 0L)
            databaseFile.copyTo(databaseSnapshot, overwrite = true)
            walFile.copyTo(walSnapshot, overwrite = true)
        } finally {
            plaintextDatabase.close()
        }
        databaseSnapshot.copyTo(databaseFile, overwrite = true)
        walSnapshot.copyTo(File("${databaseFile.absolutePath}-wal"), overwrite = true)
        File("${databaseFile.absolutePath}-shm").delete()

        try {
            val encryptedDatabase = SolinDatabase.get(context)
            try {
                assertVersion3DataWasMigrated(encryptedDatabase)
                val messages = encryptedDatabase.sessionDao().messagesForSession("session-1")
                assertEquals(listOf("migration canary", "wal canary"), messages.map { it.text })
            } finally {
                SolinDatabase.closeForInstrumentation()
            }
        } finally {
            deleteDatabaseArtifacts(databaseSnapshot)
        }
    }

    @Test
    fun interruptedSwapWithEncryptedPrimaryDeletesPlaintextRollbackArtifacts() {
        val plaintextSnapshot = File(context.cacheDir, "solin-plaintext-rollback-snapshot.db")
        deleteDatabaseArtifacts(plaintextSnapshot)
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            createVersion3Schema(database)
            seedVersion3Database(database)
        }
        databaseFile.copyTo(plaintextSnapshot, overwrite = true)
        try {
            val encryptedDatabase = SolinDatabase.get(context)
            encryptedDatabase.close()
            SolinDatabase.closeForInstrumentation()

            val rollbackFile = File(databaseFile.parentFile, "${databaseFile.name}.plaintext.rollback")
            plaintextSnapshot.copyTo(rollbackFile, overwrite = true)
            val candidateFile = File(databaseFile.parentFile, "${databaseFile.name}.sqlcipher.tmp")
            candidateFile.writeText("stale candidate")
            File("${candidateFile.absolutePath}-wal").writeText("stale wal")
            File("${candidateFile.absolutePath}-shm").writeText("stale shm")
            File("${rollbackFile.absolutePath}-wal").writeText("stale rollback wal")
            File("${rollbackFile.absolutePath}-shm").writeText("stale rollback shm")
            migrationStateFile().writeText("Swapping\n")

            val recoveredDatabase = SolinDatabase.get(context)
            try {
                assertVersion3DataWasMigrated(recoveredDatabase)
                assertFalse(rollbackFile.exists())
                assertFalse(File("${rollbackFile.absolutePath}-wal").exists())
                assertFalse(File("${rollbackFile.absolutePath}-shm").exists())
                assertFalse(candidateFile.exists())
                assertFalse(File("${candidateFile.absolutePath}-wal").exists())
                assertFalse(File("${candidateFile.absolutePath}-shm").exists())
                assertEquals("Completed", migrationStateFile().readText().trim())
            } finally {
                SolinDatabase.closeForInstrumentation()
            }
        } finally {
            deleteDatabaseArtifacts(plaintextSnapshot)
        }
    }

    @Test
    fun interruptedSwapWithMissingPrimaryRestoresEncryptedCandidate() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            createVersion3Schema(database)
            seedVersion3Database(database)
        }
        SolinDatabase.get(context).close()
        SolinDatabase.closeForInstrumentation()

        val candidateFile = File(databaseFile.parentFile, "${databaseFile.name}.sqlcipher.tmp")
        databaseFile.copyTo(candidateFile, overwrite = true)
        assertTrue(databaseFile.delete())
        migrationStateFile().writeText("Swapping\n")

        val recoveredDatabase = SolinDatabase.get(context)
        try {
            assertVersion3DataWasMigrated(recoveredDatabase)
            assertTrue(databaseFile.isFile)
            assertFalse(candidateFile.exists())
            assertFalse(File("${candidateFile.absolutePath}-wal").exists())
            assertFalse(File("${candidateFile.absolutePath}-shm").exists())
            assertEquals("Completed", migrationStateFile().readText().trim())
        } finally {
            SolinDatabase.closeForInstrumentation()
        }
    }

    @Test
    fun interruptedSwapUsesAtomicStateBackupToDeletePlaintextRollbackArtifacts() {
        val plaintextSnapshot = File(context.cacheDir, "solin-atomic-state-rollback-snapshot.db")
        deleteDatabaseArtifacts(plaintextSnapshot)
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            createVersion3Schema(database)
            seedVersion3Database(database)
        }
        databaseFile.copyTo(plaintextSnapshot, overwrite = true)
        try {
            SolinDatabase.get(context).close()
            SolinDatabase.closeForInstrumentation()

            val rollbackFile = File(databaseFile.parentFile, "${databaseFile.name}.plaintext.rollback")
            plaintextSnapshot.copyTo(rollbackFile, overwrite = true)
            val candidateFile = File(databaseFile.parentFile, "${databaseFile.name}.sqlcipher.tmp")
            candidateFile.writeText("stale candidate")
            val stateFile = migrationStateFile()
            assertTrue(stateFile.delete())
            migrationStateBackupFile().writeText("Swapping\n")

            val recoveredDatabase = SolinDatabase.get(context)
            try {
                assertVersion3DataWasMigrated(recoveredDatabase)
                assertFalse(rollbackFile.exists())
                assertFalse(candidateFile.exists())
                assertEquals("Completed", stateFile.readText().trim())
                assertFalse(migrationStateBackupFile().exists())
            } finally {
                SolinDatabase.closeForInstrumentation()
            }
        } finally {
            deleteDatabaseArtifacts(plaintextSnapshot)
        }
    }

    @Test
    fun encryptedDatabaseWithoutKeysetFailsClosed() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            createVersion3Schema(database)
            seedVersion3Database(database)
        }
        SolinDatabase.get(context).close()
        SolinDatabase.closeForInstrumentation()
        val keysetFile = keysetFile()
        assertTrue(keysetFile.delete())
        keysetNewFile().writeText("incomplete keyset")

        val failure = runCatching { SolinDatabase.get(context) }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(keysetFile.exists())
        assertTrue(keysetNewFile().isFile)
        assertFalse(readHeader(databaseFile).contentEquals(SQLITE_HEADER))
    }

    @Test
    fun encryptedDatabaseRecoversKeysetFromAtomicBackup() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            createVersion3Schema(database)
            seedVersion3Database(database)
        }
        SolinDatabase.get(context).close()
        SolinDatabase.closeForInstrumentation()

        val keysetFile = keysetFile()
        val keysetBackupFile = keysetBackupFile()
        assertTrue(keysetFile.renameTo(keysetBackupFile))
        assertFalse(keysetFile.exists())

        val recoveredDatabase = SolinDatabase.get(context)
        try {
            assertVersion3DataWasMigrated(recoveredDatabase)
            assertTrue(keysetFile.isFile)
            assertFalse(keysetBackupFile.exists())
        } finally {
            SolinDatabase.closeForInstrumentation()
        }
    }

    @Test
    fun encryptedDatabaseDiscardsStaleAtomicKeysetNewFile() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            createVersion3Schema(database)
            seedVersion3Database(database)
        }
        SolinDatabase.get(context).close()
        SolinDatabase.closeForInstrumentation()

        val keysetNewFile = keysetNewFile()
        keysetNewFile.writeText("incomplete keyset")

        val recoveredDatabase = SolinDatabase.get(context)
        try {
            assertVersion3DataWasMigrated(recoveredDatabase)
            assertFalse(keysetNewFile.exists())
        } finally {
            SolinDatabase.closeForInstrumentation()
        }
    }

    private fun assertVersion3DataWasMigrated(database: SolinDatabase) {
        val sessions = database.sessionDao().sessions()
        val messages = database.sessionDao().messagesForSession("session-1")

        assertEquals(1, sessions.size)
        assertEquals("Private title", sessions.single().title)
        assertEquals("migration canary", messages.first().text)
        assertEquals(MessagePrivacy.LocalOnly.name, messages.first().privacy)
        assertEquals("installed-model-path", database.modelDao().models().single().path)
        assertEquals(99L, database.downloadRecordDao().record()?.downloadManagerId)
        assertEquals("audit summary", database.toolAuditDao().recent(1).single().summary)
        assertFalse(readHeader(databaseFile).contentEquals(SQLITE_HEADER))
        assertTrue(context.noBackupFilesDir.resolve("solin-crypto/database-keyset.v1").isFile)
    }

    private fun createVersion3Schema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE `chat_sessions` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE `chat_messages` (
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
        database.execSQL(
            """
            CREATE TABLE `installed_models` (
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
        database.execSQL(
            """
            CREATE TABLE `download_records` (
                `id` TEXT NOT NULL,
                `downloadManagerId` INTEGER NOT NULL,
                `sourceJson` TEXT NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE `tool_audit_events` (
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
        database.execSQL(
            """
            CREATE TABLE `scheduled_tasks` (
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

    private fun seedVersion3Database(database: SQLiteDatabase) {
        database.execSQL(
            "INSERT INTO chat_sessions VALUES ('session-1', 'Private title', 1, 2)",
        )
        database.execSQL(
            "INSERT INTO chat_messages VALUES ('session-1', 0, 'User', 'migration canary', NULL, NULL)",
        )
        database.execSQL(
            """
            INSERT INTO installed_models VALUES (
                'model-1', 'Model', 'installed-model-path', 4, NULL, NULL, NULL, 'Verified'
            )
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO download_records VALUES ('pending', 99, '{\"url\":\"private\"}', 3)",
        )
        database.execSQL(
            """
            INSERT INTO tool_audit_events VALUES (
                'audit-1', NULL, NULL, 'tool', NULL, 'Succeeded', NULL, NULL, '', 'audit summary', 4
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO scheduled_tasks VALUES (
                'task-1', 'Reminder', 'scheduled task', 'body', 5, 'Pending', 6, 7
            )
            """.trimIndent(),
        )
        database.version = 3
    }

    private fun deleteDatabaseArtifacts(file: File) {
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        File("${file.absolutePath}-journal").delete()
        deleteArtifactSet(File("${file.parentFile}/${file.name}.plaintext.rollback"))
        deleteArtifactSet(File("${file.parentFile}/${file.name}.sqlcipher.tmp"))
    }

    private fun deleteArtifactSet(file: File) {
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        File("${file.absolutePath}-journal").delete()
    }

    private fun migrationStateFile(): File =
        context.noBackupFilesDir.resolve("solin-crypto/database-migration.v1")

    private fun migrationStateBackupFile(): File =
        File("${migrationStateFile().absolutePath}.bak")

    private fun keysetFile(): File =
        context.noBackupFilesDir.resolve("solin-crypto/database-keyset.v1")

    private fun keysetBackupFile(): File =
        File("${keysetFile().absolutePath}.bak")

    private fun keysetNewFile(): File =
        File("${keysetFile().absolutePath}.new")

    private fun readHeader(file: File): ByteArray =
        file.inputStream().use { input ->
            ByteArray(SQLITE_HEADER.size).also { header ->
                assertEquals(SQLITE_HEADER.size, input.read(header))
            }
        }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()
    }
}
