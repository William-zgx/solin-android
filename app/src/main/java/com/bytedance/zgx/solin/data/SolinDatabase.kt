package com.bytedance.zgx.solin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        InstalledModelEntity::class,
        DownloadRecordEntity::class,
        ToolAuditEventEntity::class,
        RemoteSendAuditEventEntity::class,
        MemoryDeletionEventEntity::class,
        ScheduledTaskEntity::class,
        MemoryRecordEntity::class,
        MemoryEmbeddingEntity::class,
        AgentRunEntity::class,
        AgentRunPlacementBindingEntity::class,
        AgentStepEntity::class,
        PendingAgentConfirmationEntity::class,
        AgentSkillRunCheckpointEntity::class,
        LocalStorageMigrationStateEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
abstract class SolinDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun modelDao(): ModelDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun remoteSendAuditDao(): RemoteSendAuditDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun memoryRecordDao(): MemoryRecordDao
    abstract fun memoryEmbeddingDao(): MemoryEmbeddingDao
    abstract fun memoryDeletionEventDao(): MemoryDeletionEventDao
    abstract fun memoryDeletionTransactionDao(): MemoryDeletionTransactionDao
    abstract fun agentTraceDao(): AgentTraceDao
    abstract fun runPlacementBindingDao(): RunPlacementBindingDao

    companion object {
        @Volatile
        private var INSTANCE: SolinDatabase? = null

        fun get(context: Context): SolinDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: createEncryptedDatabase(context.applicationContext)
                    .also { INSTANCE = it }
            }

        internal fun closeForInstrumentation() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun createEncryptedDatabase(context: Context): SolinDatabase {
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            val plaintextDatabase = EncryptedDatabaseMigrator.isPlaintextSqlite(databaseFile)
            val passphrase = LocalDatabaseKeyManager(context).loadOrCreatePassphrase(
                allowCreate = !databaseFile.exists() || plaintextDatabase,
            )
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            EncryptedDatabaseMigrator(
                context = context,
                databaseName = DATABASE_NAME,
                passphrase = passphrase,
                openCandidate = { candidateName, candidateFactory ->
                    databaseBuilder(context, candidateName, candidateFactory).build()
                },
            ).ensureEncrypted()
            return databaseBuilder(context, DATABASE_NAME, factory).build()
        }

        private fun databaseBuilder(
            context: Context,
            name: String,
            factory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory,
        ): RoomDatabase.Builder<SolinDatabase> =
            Room.databaseBuilder(
                context,
                SolinDatabase::class.java,
                name,
            )
                .openHelperFactory(factory)
                .addMigrations(*ALL_MIGRATIONS)

        private const val DATABASE_NAME = "solin.db"
    }
}

const val PENDING_DOWNLOAD_RECORD_ID = "pending"
