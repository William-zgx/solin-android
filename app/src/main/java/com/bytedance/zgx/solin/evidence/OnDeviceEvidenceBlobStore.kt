package com.bytedance.zgx.solin.evidence

import android.content.Context
import com.bytedance.zgx.solin.MessagePrivacy
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Filesystem-backed blob store. All writes are atomic (tmp + renameTo);
 * filenames are sha256 hex only.
 *
 * @param localRoot   directory for MessagePrivacy.LocalOnly blobs (noBackupFilesDir)
 * @param remoteRoot  directory for MessagePrivacy.RemoteEligible blobs (cacheDir)
 */
class OnDeviceEvidenceBlobStore @JvmOverloads constructor(
    private val localRoot: File,
    private val remoteRoot: File? = null,
) : EvidenceBlobStore {

    /** Convenience: use noBackupFilesDir/solin-evidence/v1 for local, cacheDir/solin-evidence/v1 for remote. */
    constructor(appContext: Context) : this(
        localRoot = appContext.noBackupFilesDir.resolve("solin-evidence").resolve("v1"),
        remoteRoot = appContext.cacheDir.resolve("solin-evidence").resolve("v1"),
    )

    init {
        listOfNotNull(localRoot, remoteRoot).forEach { it.mkdirs() }
    }

    private val locks = ConcurrentHashMap<String, Any>()

    companion object {
        private const val TAG = "OnDeviceEvidenceBlob"
        private val TMP_COUNTER = AtomicLong(0)

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
        synchronized(lockFor(sha)) {
            val dir = dirFor(privacy)
            dir.mkdirs()
            val bin = binFor(ref)
            if (bin.exists()) {
                bin.setLastModified(System.currentTimeMillis())
                writeMeta(ref)
                return ref
            }
            val tmpSuffix = "${TMP_COUNTER.incrementAndGet()}-${System.nanoTime()}"
            val tmpName = "$sha.tmp.$tmpSuffix"
            val tmp = File(dir, tmpName)
            val tmpMeta = File(dir, "$tmpName.meta")
            try {
                tmp.writeBytes(bytes)
                writeMeta(ref, tmp)
                if (tmpMeta.exists()) {
                    val renamedMeta = tmpMeta.renameTo(metaFor(ref))
                    if (!renamedMeta) {
                        metaFor(ref).writeBytes(tmpMeta.readBytes())
                        tmpMeta.delete()
                    }
                }
                if (!tmp.renameTo(bin)) {
                    // renameTo can fail across filesystems; copy then delete
                    bin.writeBytes(bytes)
                    tmp.delete()
                }
            } catch (t: Throwable) {
                runCatching { tmp.delete() }
                runCatching { tmpMeta.delete() }
                loge("putBytes failed for sha=$sha", t)
                throw IllegalStateException("Failed to persist blob $sha", t)
            }
        }
        return ref
    }

    private fun writeMeta(ref: EvidenceBlobRef, tmpHint: File? = null) {
        try {
            val meta = if (tmpHint != null) File(tmpHint.parentFile, tmpHint.name + ".meta") else metaFor(ref)
            meta.writeText(
                buildString {
                    appendLine("createdAt=${ref.createdAtMillis}")
                    appendLine("ttlUntil=${ref.ttlUntilMillis ?: 0}")
                    appendLine("mimeType=${ref.mimeType ?: "-"}")
                    appendLine("sourceType=${ref.sourceType.name}")
                    appendLine("privacy=${ref.privacy.name}")
                    appendLine("sizeBytes=${ref.sizeBytes}")
                },
                StandardCharsets.UTF_8,
            )
        } catch (t: Throwable) {
            loge("writeMeta failed for ${ref.sha256}", t)
        }
    }

    private data class Meta(
        val ttlUntil: Long?,
        val mimeType: String?,
        val sourceType: EvidenceSourceType?,
        val privacy: MessagePrivacy?,
    )

    private fun readMeta(bin: File): Meta? {
        val metaFile = metaFor(bin)
        if (!metaFile.exists()) return null
        return try {
            val lines = metaFile.readLines(StandardCharsets.UTF_8)
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
        if (!bin.exists()) return null
        return try {
            bin.setLastModified(System.currentTimeMillis()) // touch for GC
            bin.readBytes()
        } catch (t: Throwable) {
            logw("readBytes failed for ${ref.sha256}", t)
            null
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

    override fun gc() {
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
            while (total > EvidenceBlobStore.MAX_TOTAL_BYTES) {
                val oldest = remaining().minByOrNull { it.lastModified() } ?: break
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
