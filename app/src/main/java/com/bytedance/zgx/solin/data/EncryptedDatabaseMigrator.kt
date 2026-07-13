package com.bytedance.zgx.solin.data

import android.content.Context
import android.database.Cursor
import android.util.AtomicFile
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

internal class EncryptedDatabaseMigrator(
    context: Context,
    private val databaseName: String,
    private val passphrase: ByteArray,
    private val openCandidate: (String, SupportSQLiteOpenHelper.Factory) -> RoomDatabase,
) {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(databaseName)
    private val candidateFile = File(databaseFile.parentFile, "$databaseName.sqlcipher.tmp")
    private val rollbackFile = File(databaseFile.parentFile, "$databaseName.plaintext.rollback")
    private val stateFile = File(
        appContext.noBackupFilesDir.resolve("solin-crypto"),
        "database-migration.v1",
    )
    private val stateBackupFile = File("${stateFile.absolutePath}.bak")

    fun ensureEncrypted() {
        SqlCipherRuntime.ensureLoaded()
        databaseFile.parentFile?.mkdirs()
        RandomAccessFile(File(databaseFile.parentFile, "$databaseName.migration.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                recoverInterruptedSwap()
                when {
                    !databaseFile.exists() || databaseFile.length() == 0L -> writeState(Phase.Completed)
                    isPlaintextSqlite(databaseFile) -> migratePlaintext()
                    else -> verifyEncryptedDatabase(databaseFile)
                }
            }
        }
    }

    private fun recoverInterruptedSwap() {
        val phase = readState()
        if (databaseFile.isFile && databaseFile.length() > 0L) {
            if (!isPlaintextSqlite(databaseFile)) {
                when (phase) {
                    Phase.Swapping -> {
                        verifyEncryptedDatabase(databaseFile)
                        deleteDatabaseArtifacts(rollbackFile)
                        deleteDatabaseArtifacts(candidateFile)
                        writeState(Phase.Completed)
                    }
                    null,
                    Phase.Completed -> check(!hasRecoveryArtifacts()) {
                        "Encrypted primary database has unexpected migration artifacts."
                    }
                    else -> error("Encrypted primary database has an invalid migration state.")
                }
            }
            return
        }

        if (!hasRecoveryArtifacts()) {
            check(!hasSidecarArtifacts(databaseFile) && (phase == null || phase == Phase.Completed)) {
                "Missing primary database has an incomplete migration state."
            }
            return
        }
        check(phase == Phase.Swapping) {
            "Missing primary database has unexpected migration artifacts."
        }
        deleteDatabaseArtifacts(databaseFile)
        if (candidateFile.exists() && verifyEncryptedDatabaseOrNull(candidateFile)) {
            moveDatabaseArtifacts(candidateFile, databaseFile)
            verifyEncryptedDatabase(databaseFile)
            deleteDatabaseArtifacts(rollbackFile)
            deleteDatabaseArtifacts(candidateFile)
            writeState(Phase.Completed)
            return
        }
        if (rollbackFile.exists()) {
            move(rollbackFile, databaseFile)
            deleteDatabaseArtifacts(candidateFile)
            writeState(Phase.PlaintextDetected)
            return
        }
        error("Interrupted database swap has no recoverable database.")
    }

    private fun migratePlaintext() {
        writeState(Phase.PlaintextDetected)
        checkpointPlaintextWal()
        val expectedCounts = tableCounts(databaseFile, null)
        deleteDatabaseArtifacts(candidateFile)
        writeState(Phase.Exporting)
        exportPlaintextToEncrypted(candidateFile)
        verifyEncryptedDatabase(candidateFile)

        val candidateDatabase = openCandidate(
            candidateFile.name,
            SupportOpenHelperFactory(passphrase.copyOf()),
        )
        try {
            candidateDatabase.openHelper.writableDatabase
        } finally {
            candidateDatabase.close()
        }
        checkpointEncryptedWal(candidateFile)
        verifyEncryptedDatabase(candidateFile)
        val actualCounts = tableCounts(candidateFile, passphrase)
        expectedCounts.forEach { (table, count) ->
            check(actualCounts[table] == count) {
                "Encrypted database candidate lost rows from $table."
            }
        }
        deleteSidecarArtifacts(candidateFile)
        writeState(Phase.CandidateVerified)

        writeState(Phase.Swapping)
        deleteDatabaseArtifacts(rollbackFile)
        move(databaseFile, rollbackFile)
        move(candidateFile, databaseFile)
        verifyEncryptedDatabase(databaseFile)
        checkpointEncryptedWal(databaseFile)
        deletePlaintextArtifacts(rollbackFile)
        writeState(Phase.Completed)
    }

    private fun checkpointPlaintextWal() {
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use(::requireCompletedCheckpoint)
        } finally {
            database.close()
        }
        deleteSidecarArtifacts(databaseFile)
    }

    private fun checkpointEncryptedWal(file: File) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        )
        try {
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use(::requireCompletedCheckpoint)
        } finally {
            database.close()
        }
        deleteSidecarArtifacts(file)
    }

    private fun exportPlaintextToEncrypted(candidate: File) {
        SQLiteDatabase.openDatabase(
            candidate.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
        ).close()
        val source = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            "",
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
        )
        try {
            val sourceVersion = source.version
            source.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?;",
                arrayOf(candidate.absolutePath, passphrase.toString(StandardCharsets.US_ASCII)),
            )
            try {
                source.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                source.rawExecSQL("PRAGMA encrypted.user_version = $sourceVersion;")
            } finally {
                source.execSQL("DETACH DATABASE encrypted;")
            }
        } finally {
            source.close()
        }
    }

    private fun verifyEncryptedDatabase(file: File) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        )
        try {
            val sqliteIntegrity = pragmaResult(database, "PRAGMA integrity_check")
            check(sqliteIntegrity.equals("ok", ignoreCase = true)) {
                "Keyed SQLCipher database integrity verification failed: $sqliteIntegrity"
            }
        } finally {
            database.close()
        }
    }

    private fun verifyEncryptedDatabaseOrNull(file: File): Boolean =
        runCatching {
            verifyEncryptedDatabase(file)
            true
        }.getOrDefault(false)

    private fun pragmaResult(database: SQLiteDatabase, pragma: String): String? =
        database.rawQuery(pragma, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun requireCompletedCheckpoint(cursor: Cursor) {
        check(cursor.moveToFirst() && cursor.columnCount >= WAL_CHECKPOINT_RESULT_COLUMNS) {
            "WAL checkpoint did not return a complete result."
        }
        val busy = cursor.getLong(WAL_CHECKPOINT_BUSY_INDEX)
        val logFrames = cursor.getLong(WAL_CHECKPOINT_LOG_FRAMES_INDEX)
        val checkpointedFrames = cursor.getLong(WAL_CHECKPOINT_CHECKPOINTED_FRAMES_INDEX)
        check(busy == 0L && logFrames == checkpointedFrames) {
            "WAL checkpoint was incomplete: busy=$busy, logFrames=$logFrames, " +
                "checkpointedFrames=$checkpointedFrames."
        }
    }

    private fun tableCounts(file: File, key: ByteArray?): Map<String, Long> {
        if (key == null) {
            val database = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            return try {
                database.tableCounts()
            } finally {
                database.close()
            }
        }
        val encrypted = SQLiteDatabase.openDatabase(
            file.absolutePath,
            requireNotNull(key),
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        )
        return try {
            encrypted.tableCounts()
        } finally {
            encrypted.close()
        }
    }

    private fun android.database.sqlite.SQLiteDatabase.tableCounts(): Map<String, Long> =
        rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val table = cursor.getString(0)
                    rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(table)}", null).use { countCursor ->
                        check(countCursor.moveToFirst())
                        put(table, countCursor.getLong(0))
                    }
                }
            }
        }

    private fun SQLiteDatabase.tableCounts(): Map<String, Long> =
        rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            emptyArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val table = cursor.getString(0)
                    rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(table)}", emptyArray()).use { countCursor ->
                        check(countCursor.moveToFirst())
                        put(table, countCursor.getLong(0))
                    }
                }
            }
        }

    private fun move(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        check(source.renameTo(destination)) {
            "Failed to atomically move ${source.name} to ${destination.name}."
        }
    }

    private fun moveDatabaseArtifacts(source: File, destination: File) {
        move(source, destination)
        moveIfPresent(File("${source.absolutePath}-wal"), File("${destination.absolutePath}-wal"))
        moveIfPresent(File("${source.absolutePath}-shm"), File("${destination.absolutePath}-shm"))
        moveIfPresent(File("${source.absolutePath}-journal"), File("${destination.absolutePath}-journal"))
    }

    private fun moveIfPresent(source: File, destination: File) {
        if (source.exists()) {
            move(source, destination)
        }
    }

    private fun deleteDatabaseArtifacts(file: File) {
        deleteIfPresent(file)
        deleteSidecarArtifacts(file)
        deleteIfPresent(File("${file.absolutePath}-journal"))
    }

    private fun deletePlaintextArtifacts(file: File) {
        deleteDatabaseArtifacts(file)
    }

    private fun deleteSidecarArtifacts(file: File) {
        deleteIfPresent(File("${file.absolutePath}-wal"))
        deleteIfPresent(File("${file.absolutePath}-shm"))
    }

    private fun deleteIfPresent(file: File) {
        check(!file.exists() || file.delete()) {
            "Failed to delete stale database artifact ${file.name}."
        }
    }

    private fun hasRecoveryArtifacts(): Boolean =
        hasDatabaseArtifacts(candidateFile) || hasDatabaseArtifacts(rollbackFile)

    private fun hasDatabaseArtifacts(file: File): Boolean =
        file.exists() ||
            hasSidecarArtifacts(file) ||
            File("${file.absolutePath}-journal").exists()

    private fun hasSidecarArtifacts(file: File): Boolean =
        File("${file.absolutePath}-wal").exists() ||
            File("${file.absolutePath}-shm").exists()

    private fun readState(): Phase? {
        if (!stateFile.exists() && !stateBackupFile.exists()) return null
        val rawPhase = AtomicFile(stateFile).readFully().toString(StandardCharsets.UTF_8).trim()
        return runCatching { Phase.valueOf(rawPhase) }.getOrElse {
            throw IllegalStateException("Database migration state is invalid.", it)
        }
    }

    private fun writeState(phase: Phase) {
        stateFile.parentFile?.mkdirs()
        val atomicFile = AtomicFile(stateFile)
        val stream = atomicFile.startWrite()
        try {
            stream.write("${phase.name}\n".toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun quoteIdentifier(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

    private enum class Phase {
        PlaintextDetected,
        Exporting,
        CandidateVerified,
        Swapping,
        Completed,
    }

    companion object {
        private const val WAL_CHECKPOINT_RESULT_COLUMNS = 3
        private const val WAL_CHECKPOINT_BUSY_INDEX = 0
        private const val WAL_CHECKPOINT_LOG_FRAMES_INDEX = 1
        private const val WAL_CHECKPOINT_CHECKPOINTED_FRAMES_INDEX = 2

        fun isPlaintextSqlite(file: File): Boolean =
            file.isFile && file.inputStream().use { input ->
                val header = ByteArray(SQLITE_HEADER.size)
                input.read(header) == SQLITE_HEADER.size && header.contentEquals(SQLITE_HEADER)
            }

        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
    }
}

private object SqlCipherRuntime {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("sqlcipher")
        loaded = true
    }
}
