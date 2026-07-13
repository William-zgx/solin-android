package com.bytedance.zgx.solin.evidence

import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.solin.MessagePrivacy
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnDeviceEvidenceBlobStoreDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var localRoot: File
    private lateinit var remoteRoot: File
    private lateinit var store: OnDeviceEvidenceBlobStore

    @Before
    fun setUp() {
        val testId = UUID.randomUUID().toString()
        localRoot = context.noBackupFilesDir.resolve("evidence-device-test-$testId")
        remoteRoot = context.cacheDir.resolve("evidence-device-test-$testId")
        store = OnDeviceEvidenceBlobStore(localRoot, remoteRoot, context)
    }

    @After
    fun tearDown() {
        localRoot.deleteRecursively()
        remoteRoot.deleteRecursively()
    }

    @Test
    fun encryptedBlobRoundTripsThroughAndroidKeyStore() {
        val plaintext = "AES-GCM evidence roundtrip".toByteArray(StandardCharsets.UTF_8)
        val ref = store.putBytes(
            plaintext,
            mimeType = "application/octet-stream",
            sourceType = EvidenceSourceType.ToolResult,
            privacy = MessagePrivacy.LocalOnly,
        )

        val stored = localRoot.resolve("${ref.sha256}.bin").readBytes()
        assertEquals("SEV1", String(stored.copyOfRange(0, 4), StandardCharsets.US_ASCII))
        assertFalse(stored.contentEquals(plaintext))
        assertArrayEquals(plaintext, store.readBytes(ref))
    }

    @Test
    fun tamperedGcmCiphertextFailsClosed() {
        val ref = store.putText(
            "tamper-resistant evidence",
            sourceType = EvidenceSourceType.ToolResult,
            privacy = MessagePrivacy.LocalOnly,
        )
        val blobFile = localRoot.resolve("${ref.sha256}.bin")
        val tampered = blobFile.readBytes()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        blobFile.writeBytes(tampered)

        assertNull(store.readBytes(ref))
    }

    @Test
    fun verifiedLegacyPlaintextRemainsReadableAndMigratesToGcm() {
        val plaintext = "legacy plaintext evidence".toByteArray(StandardCharsets.UTF_8)
        val legacyWriter = OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
        val ref = legacyWriter.putBytes(
            plaintext,
            mimeType = "application/octet-stream",
            sourceType = EvidenceSourceType.Memory,
            privacy = MessagePrivacy.LocalOnly,
        )
        val blobFile = localRoot.resolve("${ref.sha256}.bin")
        assertArrayEquals(plaintext, blobFile.readBytes())

        assertArrayEquals(plaintext, store.readBytes(ref))
        assertEquals("SEV1", String(blobFile.readBytes().copyOfRange(0, 4), StandardCharsets.US_ASCII))
    }

    @Test
    fun atomicFileBackupIsRecoveredBeforeDecrypting() {
        val plaintext = "AtomicFile recovery evidence".toByteArray(StandardCharsets.UTF_8)
        val ref = store.putBytes(
            plaintext,
            mimeType = "application/octet-stream",
            sourceType = EvidenceSourceType.ToolResult,
            privacy = MessagePrivacy.LocalOnly,
        )
        val blobFile = localRoot.resolve("${ref.sha256}.bin")
        val backupFile = localRoot.resolve("${ref.sha256}.bin.bak")
        assertTrue(blobFile.renameTo(backupFile))
        assertFalse(blobFile.exists())

        assertArrayEquals(plaintext, store.readBytes(ref))
        assertTrue(blobFile.exists())
    }
}
