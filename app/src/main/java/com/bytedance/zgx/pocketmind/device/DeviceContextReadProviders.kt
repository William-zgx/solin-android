package com.bytedance.zgx.pocketmind.device

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.bytedance.zgx.pocketmind.multimodal.ImageTextExtractor
import com.bytedance.zgx.pocketmind.multimodal.MlKitImageTextExtractor
import com.bytedance.zgx.pocketmind.multimodal.SharedTextPreview

private const val DEFAULT_FOREGROUND_USAGE_LOOKBACK_MS = 15 * 60 * 1000L
private const val DEFAULT_MAX_NOTIFICATION_COUNT = 5
private const val DEFAULT_MAX_CONTACT_SUMMARY_COUNT = 5
private const val DEFAULT_MAX_CONTACT_SUMMARY_LOOKBACK = 20
private const val DEFAULT_MAX_RECENT_FILE_COUNT = 5
private const val DEFAULT_MAX_RECENT_IMAGE_TEXT_SCAN_COUNT = 3
private const val DEFAULT_MAX_CURRENT_SCREEN_TEXT_CHARS = 2_000
private const val MAX_RECENT_FILE_COUNT = 50
private const val MAX_RECENT_IMAGE_TEXT_SCAN_COUNT = 10
private const val MAX_CURRENT_SCREEN_TEXT_CHARS = 4_000

private const val KIND_ALL = "all"
private const val KIND_SCREENSHOTS = "screenshots"
private const val KIND_IMAGES = "images"
private const val KIND_VIDEOS = "videos"
private const val KIND_AUDIO = "audio"
private const val KIND_DOCUMENTS = "documents"
private const val KIND_DOWNLOADS = "downloads"
private const val KIND_OTHERS = "others"

interface ForegroundAppProvider {
    fun currentForegroundApp(): ForegroundAppReadResult
}

interface NotificationSummaryProvider {
    fun recentNotifications(maxCount: Int = DEFAULT_MAX_NOTIFICATION_COUNT): NotificationSummaryReadResult
}

interface ContactSummaryProvider {
    fun queryContacts(query: String, maxCount: Int = DEFAULT_MAX_CONTACT_SUMMARY_COUNT): ContactSummaryReadResult
}

interface RecentFileProvider {
    fun recentFiles(kind: String = KIND_ALL, maxCount: Int = DEFAULT_MAX_RECENT_FILE_COUNT): RecentFileReadResult
}

interface RecentImageTextProvider {
    fun extractRecentImageText(
        kind: String = KIND_SCREENSHOTS,
        maxCount: Int = DEFAULT_MAX_RECENT_IMAGE_TEXT_SCAN_COUNT,
    ): RecentImageTextReadResult
}

interface CurrentScreenTextProvider {
    fun currentScreenText(maxChars: Int = DEFAULT_MAX_CURRENT_SCREEN_TEXT_CHARS): CurrentScreenTextReadResult
}

data class ForegroundAppInfo(
    val packageName: String,
    val appLabel: String,
    val lastTimeUsedMillis: Long,
)

sealed class ForegroundAppReadResult {
    data class Available(val appInfo: ForegroundAppInfo) : ForegroundAppReadResult()
    data class PermissionDenied(val reason: String) : ForegroundAppReadResult()
    data class Failed(val reason: String) : ForegroundAppReadResult()
}

data class NotificationSummaryItem(
    val id: Int,
    val title: String,
    val isOngoing: Boolean,
    val postTimeMillis: Long,
)

sealed class NotificationSummaryReadResult {
    data class Available(val items: List<NotificationSummaryItem>) : NotificationSummaryReadResult()
    data class PermissionDenied(val reason: String) : NotificationSummaryReadResult()
    data class Failed(val reason: String) : NotificationSummaryReadResult()
}

data class ContactSummaryItem(
    val name: String,
    val phone: String,
)

sealed class ContactSummaryReadResult {
    data class Available(val items: List<ContactSummaryItem>) : ContactSummaryReadResult()
    data class PermissionDenied(val reason: String) : ContactSummaryReadResult()
    data class Failed(val reason: String) : ContactSummaryReadResult()
}

data class RecentFileItem(
    val id: Long,
    val name: String,
    val mimeType: String,
    val kind: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

sealed class RecentFileReadResult {
    data class Available(val items: List<RecentFileItem>) : RecentFileReadResult()
    data class PermissionDenied(
        val reason: String,
        val retryable: Boolean = true,
    ) : RecentFileReadResult()
    data class Failed(val reason: String) : RecentFileReadResult()
}

internal data class RecentFileRow(
    val id: Long,
    val name: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModifiedSeconds: Long,
)

internal data class RecentFileKindFilter(
    val allowedKind: String,
    val outputKind: String? = null,
    val selection: String? = null,
    val selectionArgs: Array<String>? = null,
)

internal fun collectRecentFileItems(
    rows: Sequence<RecentFileRow>,
    filter: RecentFileKindFilter,
    maxCount: Int,
): List<RecentFileItem> {
    val files = mutableListOf<RecentFileItem>()
    val iterator = rows.iterator()
    while (files.size < maxCount && iterator.hasNext()) {
        val row = iterator.next()
        val mimeType = row.mimeType.orEmpty()
        val kindFromMime = normalizedRecentFileKindFromMimeType(mimeType)
        if (filter.allowedKind != KIND_ALL && kindFromMime != filter.allowedKind) continue

        files += RecentFileItem(
            id = row.id,
            name = row.name.orEmpty().ifBlank { "未命名文件" },
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            kind = filter.outputKind ?: kindFromMime,
            sizeBytes = row.sizeBytes.coerceAtLeast(0L),
            lastModifiedMillis = row.lastModifiedSeconds.coerceAtLeast(0L) * 1000L,
        )
    }
    return files
}

private fun normalizedRecentFileKindFromMimeType(mimeType: String): String {
    val normalized = mimeType.lowercase()
    return when {
        normalized.startsWith("image/") -> KIND_IMAGES
        normalized.startsWith("video/") -> KIND_VIDEOS
        normalized.startsWith("audio/") -> KIND_AUDIO
        normalized.startsWith("application/") || normalized.startsWith("text/") -> KIND_DOCUMENTS
        else -> KIND_OTHERS
    }
}

data class RecentImageTextItem(
    val name: String,
    val mimeType: String,
    val kind: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val text: String,
    val truncated: Boolean,
)

sealed class RecentImageTextReadResult {
    data class Available(
        val item: RecentImageTextItem?,
        val scannedCount: Int,
    ) : RecentImageTextReadResult()

    data class PermissionDenied(val reason: String) : RecentImageTextReadResult()
    data class Failed(val reason: String) : RecentImageTextReadResult()
}

data class CurrentScreenTextSnapshot(
    val text: String,
    val packageName: String?,
    val capturedAtMillis: Long,
    val nodeCount: Int,
    val truncated: Boolean,
    val structureSummary: String = "",
    val structureSummaryIncluded: Boolean = structureSummary.isNotBlank(),
)

sealed class CurrentScreenTextReadResult {
    data class Available(val snapshot: CurrentScreenTextSnapshot) : CurrentScreenTextReadResult()
    data class PermissionDenied(val reason: String) : CurrentScreenTextReadResult()
    data class Failed(val reason: String) : CurrentScreenTextReadResult()
}

class AndroidCurrentScreenTextProvider : CurrentScreenTextProvider {
    override fun currentScreenText(maxChars: Int): CurrentScreenTextReadResult =
        PocketMindAccessibilityService.readCurrentScreenText(
            maxChars = maxChars.coerceIn(1, MAX_CURRENT_SCREEN_TEXT_CHARS),
        )
}

class AndroidContactSummaryProvider(
    private val context: Context,
) : ContactSummaryProvider {
    override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return ContactSummaryReadResult.Failed("查询关键词不能为空")
        }
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ContactSummaryReadResult.PermissionDenied("未授权“读取联系人”权限")
        }
        val normalizedMax = maxCount.coerceIn(1, DEFAULT_MAX_CONTACT_SUMMARY_LOOKBACK)
        val likeArg = "%$trimmedQuery%"
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"

        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                CONTACT_PROJECTION,
                selection,
                arrayOf(likeArg, likeArg),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
            ) ?: return ContactSummaryReadResult.Failed("通讯录数据源不可用")

            val results = linkedMapOf<String, ContactSummaryItem>()
            cursor.use {
                val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val phoneIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex).orEmpty().trim().ifBlank { "未知联系人" }
                    val phone = it.getString(phoneIndex).orEmpty().trim()
                    if (phone.isBlank()) continue

                    val key = "$name|${phone.replace("\\s+".toRegex(), "")}"
                    if (!results.containsKey(key)) {
                        results[key] = ContactSummaryItem(
                            name = name,
                            phone = phone,
                        )
                        if (results.size >= normalizedMax) break
                    }
                }
            }
            ContactSummaryReadResult.Available(results.values.toList())
        } catch (_: SecurityException) {
            ContactSummaryReadResult.PermissionDenied("未授权“读取联系人”权限")
        } catch (throwable: Throwable) {
            ContactSummaryReadResult.Failed(
                throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName,
            )
        }
    }

    private companion object {
        val CONTACT_PROJECTION = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
    }
}

class AndroidForegroundAppProvider internal constructor(
    private val usageStatsSource: ForegroundUsageStatsSource,
    private val appLabelResolver: ForegroundAppLabelResolver,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : ForegroundAppProvider {
    constructor(context: Context) : this(
        usageStatsSource = AndroidForegroundUsageStatsSource(context),
        appLabelResolver = AndroidForegroundAppLabelResolver(context.packageManager),
    )

    override fun currentForegroundApp(): ForegroundAppReadResult {
        if (!usageStatsSource.isSupported) {
            return ForegroundAppReadResult.Failed(
                "当前系统版本不支持查询前台应用",
            )
        }
        if (!usageStatsSource.hasUsageStatsPermission()) {
            return ForegroundAppReadResult.PermissionDenied("未授权“查看应用使用情况”权限")
        }
        val now = clockMillis()
        val usages = try {
            usageStatsSource.queryUsageStats(
                startTimeMillis = now - DEFAULT_FOREGROUND_USAGE_LOOKBACK_MS,
                endTimeMillis = now,
            )
        } catch (securityException: SecurityException) {
            return ForegroundAppReadResult.PermissionDenied("未授权“查看应用使用情况”权限")
        }
        if (usages.isEmpty()) {
            return ForegroundAppReadResult.Failed("未能查询到应用使用统计")
        }
        val current = usages.maxByOrNull { it.lastTimeUsed } ?: return ForegroundAppReadResult.Failed(
            "未能识别当前应用",
        )
        if (current.packageName.isBlank()) {
            return ForegroundAppReadResult.Failed("当前前台应用包名为空")
        }
        val appLabel = appLabelResolver.labelFor(current.packageName) ?: current.packageName
        return ForegroundAppReadResult.Available(
            ForegroundAppInfo(
                packageName = current.packageName,
                appLabel = appLabel,
                lastTimeUsedMillis = current.lastTimeUsed,
            ),
        )
    }
}

internal data class ForegroundUsageSnapshot(
    val packageName: String,
    val lastTimeUsed: Long,
)

internal interface ForegroundUsageStatsSource {
    val isSupported: Boolean
    fun hasUsageStatsPermission(): Boolean
    fun queryUsageStats(startTimeMillis: Long, endTimeMillis: Long): List<ForegroundUsageSnapshot>
}

internal interface ForegroundAppLabelResolver {
    fun labelFor(packageName: String): String?
}

private class AndroidForegroundUsageStatsSource(
    private val context: Context,
) : ForegroundUsageStatsSource {
    private val usageStatsManager: UsageStatsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } else {
            null
        }

    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && usageStatsManager != null

    override fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val packageName = context.packageName
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun queryUsageStats(
        startTimeMillis: Long,
        endTimeMillis: Long,
    ): List<ForegroundUsageSnapshot> =
        usageStatsManager
            ?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTimeMillis,
                endTimeMillis,
            )
            .orEmpty()
            .map { usage ->
                ForegroundUsageSnapshot(
                    packageName = usage.packageName.orEmpty(),
                    lastTimeUsed = usage.lastTimeUsed,
                )
            }
}

private class AndroidForegroundAppLabelResolver(
    private val packageManager: PackageManager,
) : ForegroundAppLabelResolver {
    override fun labelFor(packageName: String): String? =
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
}

class AndroidNotificationSummaryProvider(
    private val context: Context,
) : NotificationSummaryProvider {
    override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult {
        val normalized = maxCount.coerceIn(1, 20)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return NotificationSummaryReadResult.Failed("通知服务不可用")
        if (!manager.areNotificationsEnabled()) {
            return NotificationSummaryReadResult.PermissionDenied("未开启应用通知权限")
        }
        val notifications = runCatching {
            manager.activeNotifications
        }.getOrNull() ?: return NotificationSummaryReadResult.Failed("读取通知失败")

        val packageName = context.packageName
        val items = notifications
            .asSequence()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.postTime }
            .take(normalized)
            .map { statusBarNotification ->
                val extras = statusBarNotification.notification.extras
                val title = extras.getCharSequence("android.title")?.toString().orEmpty()
                NotificationSummaryItem(
                    id = statusBarNotification.id,
                    title = title.ifBlank { "(无标题)" },
                    isOngoing = statusBarNotification.isOngoing,
                    postTimeMillis = statusBarNotification.postTime,
                )
            }
            .toList()
        return NotificationSummaryReadResult.Available(items)
    }
}

class AndroidRecentFileProvider(
    private val context: Context,
) : RecentFileProvider {
    override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult {
        val normalizedKind = normalizeKind(kind)
        if (!hasReadPermissionForKind(normalizedKind)) {
            return RecentFileReadResult.PermissionDenied(
                reason = permissionMessageForKind(normalizedKind),
                retryable = isRuntimePermissionRetryableKind(normalizedKind),
            )
        }
        val normalizedMaxCount = maxCount.coerceIn(1, MAX_RECENT_FILE_COUNT)
        val filter = buildKindFilter(normalizedKind)

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                FILE_PROJECTION,
                filter.selection,
                filter.selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            ) ?: return RecentFileReadResult.Failed("文件服务不可用")

            val files = cursor.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeTypeIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val modifiedIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                val rows = generateSequence {
                    if (!it.moveToNext()) {
                        null
                    } else {
                        RecentFileRow(
                            id = it.getLong(idIndex),
                            name = it.getString(nameIndex),
                            mimeType = it.getString(mimeTypeIndex),
                            sizeBytes = it.getLong(sizeIndex),
                            lastModifiedSeconds = it.getLong(modifiedIndex),
                        )
                    }
                }
                collectRecentFileItems(rows, filter, normalizedMaxCount)
            }

            RecentFileReadResult.Available(files)
        } catch (_: SecurityException) {
            RecentFileReadResult.PermissionDenied("未授权“读取文件”权限")
        } catch (throwable: Throwable) {
            RecentFileReadResult.Failed(
                throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName,
            )
        }
    }

    private fun buildKindFilter(kind: String): RecentFileKindFilter =
        when (kind) {
            KIND_ALL ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaFilterForGrantedPermissions()
                } else {
                    RecentFileKindFilter(allowedKind = KIND_ALL)
                }

            KIND_IMAGES -> RecentFileKindFilter(
                allowedKind = KIND_IMAGES,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("image/%"),
            )

            KIND_SCREENSHOTS -> RecentFileKindFilter(
                allowedKind = KIND_IMAGES,
                outputKind = KIND_SCREENSHOTS,
                selection = screenshotSelection(),
                selectionArgs = screenshotSelectionArgs(),
            )

            KIND_VIDEOS -> RecentFileKindFilter(
                allowedKind = KIND_VIDEOS,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("video/%"),
            )

            KIND_AUDIO -> RecentFileKindFilter(
                allowedKind = KIND_AUDIO,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("audio/%"),
            )

            KIND_DOCUMENTS -> RecentFileKindFilter(
                allowedKind = KIND_DOCUMENTS,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("application/%", "text/%", "application/x-%"),
            )

            KIND_DOWNLOADS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    RecentFileKindFilter(
                        allowedKind = KIND_ALL,
                        selection = "LOWER(${MediaStore.Files.FileColumns.RELATIVE_PATH}) LIKE ?",
                        selectionArgs = arrayOf("%download%"),
                    )
                } else {
                    RecentFileKindFilter(allowedKind = KIND_ALL)
                }

            KIND_OTHERS -> RecentFileKindFilter(
                allowedKind = KIND_OTHERS,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IS NULL",
            )

            else -> RecentFileKindFilter(allowedKind = KIND_ALL)
        }

    private fun mediaFilterForGrantedPermissions(): RecentFileKindFilter {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
            clauses += "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
            args += "image/%"
        }
        if (hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) {
            clauses += "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
            args += "video/%"
        }
        if (hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) {
            clauses += "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
            args += "audio/%"
        }
        return RecentFileKindFilter(
            allowedKind = KIND_ALL,
            selection = clauses.joinToString(separator = " OR "),
            selectionArgs = args.toTypedArray(),
        )
    }

    private fun normalizeKind(input: String): String =
        when (input.lowercase()) {
            KIND_SCREENSHOTS -> KIND_SCREENSHOTS
            KIND_IMAGES -> KIND_IMAGES
            KIND_VIDEOS -> KIND_VIDEOS
            KIND_AUDIO -> KIND_AUDIO
            KIND_DOCUMENTS -> KIND_DOCUMENTS
            KIND_DOWNLOADS -> KIND_DOWNLOADS
            KIND_OTHERS -> KIND_OTHERS
            KIND_ALL -> KIND_ALL
            else -> KIND_ALL
        }

    private fun hasReadPermissionForKind(kind: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return when (kind) {
                KIND_SCREENSHOTS -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                KIND_IMAGES -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                KIND_VIDEOS -> hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                KIND_AUDIO -> hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                KIND_ALL -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(Manifest.permission.READ_MEDIA_VIDEO) ||
                    hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                else -> false
            }
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun permissionMessageForKind(kind: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            kind in setOf(KIND_DOCUMENTS, KIND_DOWNLOADS, KIND_OTHERS)
        ) {
            "当前 Android 版本需要通过系统文件选择器授权非媒体文件"
        } else {
            "未授权“读取文件”权限"
        }

    private fun isRuntimePermissionRetryableKind(kind: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            kind !in setOf(KIND_DOCUMENTS, KIND_DOWNLOADS, KIND_OTHERS)

    private fun screenshotSelection(): String {
        val nameClauses = SCREENSHOT_MARKERS.joinToString(separator = " OR ") {
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        }
        val pathClauses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SCREENSHOT_MARKERS.joinToString(separator = " OR ") {
                "LOWER(${MediaStore.Files.FileColumns.RELATIVE_PATH}) LIKE ?"
            }
        } else {
            ""
        }
        val screenshotClauses = listOf(nameClauses, pathClauses)
            .filter { it.isNotBlank() }
            .joinToString(separator = " OR ")
        return "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? AND ($screenshotClauses)"
    }

    private fun screenshotSelectionArgs(): Array<String> {
        val markers = SCREENSHOT_MARKERS.map { "%$it%" }
        val args = mutableListOf("image/%")
        args += markers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            args += markers
        }
        return args.toTypedArray()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val SCREENSHOT_MARKERS = listOf(
            "screenshot",
            "screen_shot",
            "screencap",
            "screen capture",
            "截屏",
            "截图",
        )

        val FILE_PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
    }
}

class AndroidRecentImageTextProvider(
    private val context: Context,
    private val imageTextExtractor: ImageTextExtractor = MlKitImageTextExtractor(context),
) : RecentImageTextProvider {
    override fun extractRecentImageText(kind: String, maxCount: Int): RecentImageTextReadResult {
        val normalizedKind = normalizeImageTextKind(kind)
        if (!hasImageReadPermission()) {
            return RecentImageTextReadResult.PermissionDenied("未授权“读取图片”权限")
        }
        val normalizedMaxCount = maxCount.coerceIn(1, MAX_RECENT_IMAGE_TEXT_SCAN_COUNT)
        val filter = buildImageTextFilter(normalizedKind)

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                IMAGE_TEXT_PROJECTION,
                filter.selection,
                filter.selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            ) ?: return RecentImageTextReadResult.Failed("图片服务不可用")

            var scannedCount = 0
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeTypeIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val modifiedIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (it.moveToNext() && scannedCount < normalizedMaxCount) {
                    scannedCount += 1
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        it.getLong(idIndex),
                    )
                    val preview = imageTextExtractor.extract(uri) ?: continue
                    return RecentImageTextReadResult.Available(
                        item = preview.toRecentImageTextItem(
                            name = it.getString(nameIndex).orEmpty().ifBlank { "未命名图片" },
                            mimeType = it.getString(mimeTypeIndex).orEmpty().ifBlank { "image/*" },
                            kind = normalizedKind,
                            sizeBytes = it.getLong(sizeIndex).coerceAtLeast(0L),
                            lastModifiedMillis = it.getLong(modifiedIndex).coerceAtLeast(0L) * 1000L,
                        ),
                        scannedCount = scannedCount,
                    )
                }
            }

            RecentImageTextReadResult.Available(item = null, scannedCount = scannedCount)
        } catch (_: SecurityException) {
            RecentImageTextReadResult.PermissionDenied("未授权“读取图片”权限")
        } catch (_: Throwable) {
            RecentImageTextReadResult.Failed("图片 OCR 服务不可用")
        }
    }

    private fun buildImageTextFilter(kind: String): ImageTextFilter =
        when (kind) {
            KIND_SCREENSHOTS -> ImageTextFilter(
                selection = screenshotSelection(),
                selectionArgs = screenshotSelectionArgs(),
            )

            else -> ImageTextFilter(
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("image/%"),
            )
        }

    private fun normalizeImageTextKind(input: String): String =
        when (input.lowercase()) {
            KIND_IMAGES -> KIND_IMAGES
            KIND_SCREENSHOTS -> KIND_SCREENSHOTS
            else -> KIND_SCREENSHOTS
        }

    private fun hasImageReadPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun screenshotSelection(): String {
        val nameClauses = IMAGE_TEXT_SCREENSHOT_MARKERS.joinToString(separator = " OR ") {
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        }
        val pathClauses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            IMAGE_TEXT_SCREENSHOT_MARKERS.joinToString(separator = " OR ") {
                "LOWER(${MediaStore.Files.FileColumns.RELATIVE_PATH}) LIKE ?"
            }
        } else {
            ""
        }
        val screenshotClauses = listOf(nameClauses, pathClauses)
            .filter { it.isNotBlank() }
            .joinToString(separator = " OR ")
        return "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? AND ($screenshotClauses)"
    }

    private fun screenshotSelectionArgs(): Array<String> {
        val markers = IMAGE_TEXT_SCREENSHOT_MARKERS.map { "%$it%" }
        val args = mutableListOf("image/%")
        args += markers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            args += markers
        }
        return args.toTypedArray()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun SharedTextPreview.toRecentImageTextItem(
        name: String,
        mimeType: String,
        kind: String,
        sizeBytes: Long,
        lastModifiedMillis: Long,
    ): RecentImageTextItem =
        RecentImageTextItem(
            name = name,
            mimeType = mimeType,
            kind = kind,
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModifiedMillis,
            text = text,
            truncated = truncated,
        )

    private data class ImageTextFilter(
        val selection: String,
        val selectionArgs: Array<String>,
    )

    private companion object {
        val IMAGE_TEXT_SCREENSHOT_MARKERS = listOf(
            "screenshot",
            "screen_shot",
            "screencap",
            "screen capture",
            "截屏",
            "截图",
        )

        val IMAGE_TEXT_PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
    }
}
