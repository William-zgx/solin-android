package com.bytedance.zgx.solin.evidence

import android.content.Context
import android.util.AtomicFile
import com.bytedance.zgx.solin.MessagePrivacy
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Filesystem-backed blob store with authenticated AES encryption.
 *
 * All writes use AtomicFile; filenames are sha256 hex of the
 * **plaintext** (so deduplication still works across identical inputs). File
 * contents are encrypted with AES/GCM/NoPadding using a key stored in
 * AndroidKeyStore. A versioned envelope precedes the random nonce and ciphertext.
 *
 * A production store fails closed if AndroidKeyStore is unavailable or a blob
 * cannot be authenticated or matched to its content address. Verified legacy
 * plaintext and CBC blobs remain readable and are migrated to the GCM format.
 * The no-context constructor remains plaintext only for local JVM tests.
 *
 * @param localRoot   directory for MessagePrivacy.LocalOnly blobs (noBackupFilesDir)
 * @param remoteRoot  directory for MessagePrivacy.RemoteEligible blobs (cacheDir)
 * @param appContext  optional application context; when provided, blobs are encrypted.
 *                    Pass null to disable encryption (e.g. in unit tests).
 */
class OnDeviceEvidenceBlobStore @JvmOverloads constructor(
    private val localRoot: File,
    private val remoteRoot: File? = null,
    private val appContext: Context? = null,
    private val maxTotalBytes: Long = EvidenceBlobStore.MAX_TOTAL_BYTES,
) : EvidenceBlobStore {

    /** Convenience: use noBackupFilesDir/solin-evidence/v1 for local, cacheDir/solin-evidence/v1 for remote. */
    constructor(appContext: Context) : this(
        localRoot = appContext.noBackupFilesDir.resolve("solin-evidence").resolve("v1"),
        remoteRoot = appContext.cacheDir.resolve("solin-evidence").resolve("v1"),
        appContext = appContext.applicationContext,
    )

    init {
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive." }
        listOfNotNull(localRoot, remoteRoot).forEach { it.mkdirs() }
    }

    private val locks = ConcurrentHashMap<String, Any>()

    // ------------------------------------------------------------------
    // Encryption support
    // ------------------------------------------------------------------

    private val encryptor: BlobEncryptor? by lazy {
        if (appContext == null) null else AndroidKeyStoreBlobEncryptor()
    }

    private val legacyDecryptor: BlobDecryptor? by lazy {
        if (appContext == null) null else runCatching { LegacyCbcBlobDecryptor() }
            .onFailure { logw("Legacy evidence key is unavailable", it) }
            .getOrNull()
    }

    /**
     * Encrypts [plaintext] if an encryptor is available.
     */
    private fun maybeEncrypt(plaintext: ByteArray): ByteArray =
        encryptor?.encrypt(plaintext) ?: plaintext

    /**
     * Decrypts and content-address verifies [data]. Production accepts only
     * authenticated GCM blobs or verified legacy plaintext/CBC blobs.
     */
    private fun maybeDecrypt(ref: EvidenceBlobRef, data: ByteArray): DecodedBlob? {
        val currentEncryptor = encryptor
        if (currentEncryptor == null) {
            return data.takeIf { sha256Hex(it) == ref.sha256 }
                ?.let { DecodedBlob(it, migrateToGcm = false) }
        }

        if (sha256Hex(data) == ref.sha256) {
            return DecodedBlob(data, migrateToGcm = true)
        }

        val plaintext = if (currentEncryptor.hasEnvelope(data)) {
            currentEncryptor.decrypt(data)
        } else {
            legacyDecryptor?.decrypt(data)
        } ?: return null
        return plaintext.takeIf { sha256Hex(it) == ref.sha256 }
            ?.let { DecodedBlob(it, migrateToGcm = !currentEncryptor.hasEnvelope(data)) }
    }

    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "OnDeviceEvidenceBlob"
        private fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun loge(msg: String, t: Throwable? = null) {
            runCatching { android.util.Log.e(TAG, msg, t) }
                .onFailure { println("E/$TAG: $msg ${t?.message}") }
        }

        private fun logw(msg: String, t: Throwable? = null) {
            runCatching { android.util.Log.w(TAG, msg, t) }
                .onFailure { println("W/$TAG: $msg ${t?.message}") }
        }
    }

    private fun resolveRemote(): File = remoteRoot ?: localRoot

    private fun dirFor(privacy: MessagePrivacy): File =
        if (privacy == MessagePrivacy.LocalOnly) localRoot else resolveRemote()

    private fun binFor(ref: EvidenceBlobRef): File = File(dirFor(ref.privacy), ref.sha256 + ".bin")

    private fun metaFor(ref: EvidenceBlobRef): File = File(dirFor(ref.privacy), ref.sha256 + ".meta")

    private fun metaFor(bin: File): File = File(bin.parentFile, bin.name.removeSuffix(".bin") + ".meta")

    private fun lockFor(sha: String): Any = locks.computeIfAbsent(sha) { Any() }

    override fun putText(
        text: String,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy,
        ttlMs: Long,
    ): EvidenceBlobRef {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return putBytes(bytes, "text/plain; charset=utf-8", sourceType, privacy, ttlMs)
    }

    override fun putBytes(
        bytes: ByteArray,
        mimeType: String?,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy,
        ttlMs: Long,
    ): EvidenceBlobRef {
        val sha = sha256Hex(bytes)
        val ttlUntil = if (ttlMs > 0) System.currentTimeMillis() + ttlMs else null
        val ref = EvidenceBlobRef.fromSha256(sha, bytes.size.toLong(), mimeType, privacy, sourceType, ttlUntil)
        val storedBytes = maybeEncrypt(bytes)
        require(storedBytes.size.toLong() <= maxTotalBytes) {
            "Evidence blob exceeds the storage limit."
        }
        synchronized(lockFor(sha)) {
            val dir = dirFor(privacy)
            dir.mkdirs()
            val bin = binFor(ref)
            if (bin.exists()) {
                bin.setLastModified(System.currentTimeMillis())
                writeMeta(ref)
                return ref
            }
            var wroteBin = false
            try {
                writeAtomically(bin, storedBytes)
                wroteBin = true
                writeMeta(ref)
            } catch (t: Throwable) {
                if (wroteBin) {
                    runCatching { bin.delete() }
                    runCatching { metaFor(ref).delete() }
                }
                loge("putBytes failed for sha=$sha", t)
                throw IllegalStateException("Failed to persist blob $sha", t)
            }
        }
        gc(excluding = binFor(ref))
        if (!binFor(ref).exists()) {
            throw IllegalStateException("Evidence blob was evicted before it could be referenced.")
        }
        return ref
    }

    private fun writeMeta(ref: EvidenceBlobRef) {
        writeAtomically(
            metaFor(ref),
            buildString {
                appendLine("createdAt=${ref.createdAtMillis}")
                appendLine("ttlUntil=${ref.ttlUntilMillis ?: 0}")
                appendLine("mimeType=${ref.mimeType ?: "-"}")
                appendLine("sourceType=${ref.sourceType.name}")
                appendLine("privacy=${ref.privacy.name}")
                appendLine("sizeBytes=${ref.sizeBytes}")
                appendLine("encrypted=${encryptor != null}")
                appendLine("envelope=${if (encryptor != null) "aes-gcm-v1" else "plaintext-test-only"}")
            }.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        if (appContext == null) {
            file.outputStream().use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            return
        }
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(stream) }
            throw error
        }
    }

    private fun readAtomically(file: File): ByteArray =
        if (appContext == null) file.readBytes() else AtomicFile(file).readFully()

    private fun migrateLegacyBlob(ref: EvidenceBlobRef, bin: File, plaintext: ByteArray) {
        val currentEncryptor = encryptor ?: return
        runCatching {
            writeAtomically(bin, currentEncryptor.encrypt(plaintext))
            writeMeta(ref)
        }.onFailure { logw("Failed to migrate legacy blob ${ref.sha256}", it) }
    }

    private data class Meta(
        val ttlUntil: Long?,
        val mimeType: String?,
        val sourceType: EvidenceSourceType?,
        val privacy: MessagePrivacy?,
    )

    private fun readMeta(bin: File): Meta? {
        val metaFile = metaFor(bin)
        return try {
            val lines = String(readAtomically(metaFile), StandardCharsets.UTF_8).lineSequence()
            val map = lines.mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq < 0) null else line.substring(0, eq) to line.substring(eq + 1)
            }.toMap()
            Meta(
                ttlUntil = map["ttlUntil"]?.toLongOrNull()?.takeIf { it > 0 },
                mimeType = map["mimeType"]?.takeIf { it != "-" },
                sourceType = map["sourceType"]?.let { runCatching { EvidenceSourceType.valueOf(it) }.getOrNull() },
                privacy = map["privacy"]?.let { runCatching { MessagePrivacy.valueOf(it) }.getOrNull() },
            )
        } catch (t: Throwable) {
            logw("readMeta failed for ${bin.name}", t)
            null
        }
    }

    override fun readText(ref: EvidenceBlobRef, offset: Int, limit: Int): TextWindow {
        val bytes = readBytes(ref) ?: return TextWindow("", 0, truncated = true, totalChars = 0)
        val all = String(bytes, StandardCharsets.UTF_8)
        val total = all.length
        if (offset >= total) return TextWindow("", offset, truncated = false, totalChars = total)
        val end = minOf(total, offset + limit)
        val window = all.substring(offset, end)
        return TextWindow(window, offset, truncated = end < total, totalChars = total)
    }

    override fun readBytes(ref: EvidenceBlobRef): ByteArray? {
        if (!EvidenceBlobRef.SHA256_HEX.matches(ref.sha256)) return null
        val bin = binFor(ref)
        return synchronized(lockFor(ref.sha256)) {
            try {
                val storedBytes = readAtomically(bin)
                bin.setLastModified(System.currentTimeMillis()) // touch for GC after AtomicFile recovery
                val decoded = maybeDecrypt(ref, storedBytes) ?: return@synchronized null
                if (decoded.migrateToGcm) {
                    migrateLegacyBlob(ref, bin, decoded.plaintext)
                }
                decoded.plaintext
            } catch (t: Throwable) {
                logw("readBytes failed for ${ref.sha256}", t)
                null
            }
        }
    }

    override fun headTailText(ref: EvidenceBlobRef, headChars: Int, tailChars: Int): HeadTailResult {
        val all = readBytes(ref)?.toString(StandardCharsets.UTF_8)
            ?: return HeadTailResult("", ref, truncated = true, omittedChars = 0)
        if (all.length <= headChars + tailChars) {
            return HeadTailResult(all, ref, truncated = false, omittedChars = 0)
        }
        val head = all.take(headChars)
        val tail = all.takeLast(tailChars)
        val omitted = all.length - headChars - tailChars
        val marker = "\n… ($omitted chars omitted; full content at ${ref.uri}) …\n"
        return HeadTailResult(head + marker + tail, ref, truncated = true, omittedChars = omitted)
    }

    override fun gc() = gc(excluding = null)

    private fun gc(excluding: File?) {
        val now = System.currentTimeMillis()
        listOfNotNull(localRoot, remoteRoot).forEach { root ->
            val bins = root.listFiles { _, n -> n.endsWith(".bin") } ?: return@forEach
            // 1. TTL sweep
            for (bin in bins) {
                val meta = readMeta(bin) ?: continue
                val ttl = meta.ttlUntil
                if (ttl != null && ttl < now) {
                    bin.delete()
                    metaFor(bin).delete()
                }
            }
            // 2. Size cap sweep (oldest first)
            fun remaining(): Array<File> = root.listFiles { _, n -> n.endsWith(".bin") } ?: emptyArray()
            var total = remaining().sumOf { it.length() }
            while (total > maxTotalBytes) {
                val oldest = remaining()
                    .asSequence()
                    .filterNot { it == excluding }
                    .minByOrNull { it.lastModified() }
                    ?: break
                val size = oldest.length()
                if (oldest.delete() && metaFor(oldest).delete()) {
                    total -= size
                } else {
                    break
                }
            }
        }
    }

    override fun clear() {
        listOfNotNull(localRoot, remoteRoot).forEach { root ->
            root.listFiles()?.forEach { it.delete() }
        }
    }
}

// ==========================================================================
// Encryption internals
// ==========================================================================

/**
 * Abstraction over blob encryption so [OnDeviceEvidenceBlobStore] can remain
 * testable with a no-op or fake.
 */
private interface BlobDecryptor {
    fun decrypt(data: ByteArray): ByteArray
}

private interface BlobEncryptor : BlobDecryptor {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun hasEnvelope(data: ByteArray): Boolean
}

/**
 * AES/GCM/NoPadding encryptor backed by a key stored in AndroidKeyStore.
 *
 * The key is named "solin_evidence_aes_gcm_v1" and is generated once on first use.
 * If AndroidKeyStore is unavailable the constructor throws and callers fail
 * closed rather than storing plaintext evidence.
 */
private class AndroidKeyStoreBlobEncryptor : BlobEncryptor {

    private val keyStore: java.security.KeyStore =
        java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val secretKey: javax.crypto.SecretKey by lazy {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey
        existing ?: generateKey()
    }

    private fun generateKey(): javax.crypto.SecretKey {
        val generator = javax.crypto.KeyGenerator.getInstance(
            KEY_ALGORITHM, ANDROID_KEYSTORE,
        )
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return MAGIC + byteArrayOf(VERSION, nonce.size.toByte()) + nonce + ciphertext
    }

    override fun hasEnvelope(data: ByteArray): Boolean =
        data.size >= MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    override fun decrypt(data: ByteArray): ByteArray {
        check(data.size >= MAGIC.size + 2) { "Evidence envelope is truncated." }
        check(hasEnvelope(data)) {
            "Evidence envelope is not authenticated."
        }
        check(data[MAGIC.size] == VERSION) { "Evidence envelope version is unsupported." }
        val nonceLength = data[MAGIC.size + 1].toInt() and 0xff
        val nonceStart = MAGIC.size + 2
        val ciphertextStart = nonceStart + nonceLength
        check(nonceLength == GCM_NONCE_LENGTH && data.size >= ciphertextStart + GCM_TAG_BYTES) {
            "Evidence envelope nonce is invalid."
        }
        val nonce = data.copyOfRange(nonceStart, ciphertextStart)
        val ciphertext = data.copyOfRange(ciphertextStart, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "solin_evidence_aes_gcm_v1"
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
        const val VERSION: Byte = 1
        val MAGIC = byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    }
}

private class LegacyCbcBlobDecryptor : BlobDecryptor {

    private val keyStore: java.security.KeyStore =
        java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun decrypt(data: ByteArray): ByteArray {
        check(data.size > IV_LEN && (data.size - IV_LEN) % AES_BLOCK_BYTES == 0) {
            "Legacy evidence ciphertext is invalid."
        }
        val key = keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey
            ?: error("Legacy evidence key is missing.")
        val iv = data.copyOfRange(0, IV_LEN)
        val ciphertext = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "solin_evidence_key"
        const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        const val IV_LEN = 16
        const val AES_BLOCK_BYTES = 16
    }
}

private data class DecodedBlob(
    val plaintext: ByteArray,
    val migrateToGcm: Boolean,
)
