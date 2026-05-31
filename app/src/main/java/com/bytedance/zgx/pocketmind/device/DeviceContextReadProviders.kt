package com.bytedance.zgx.pocketmind.device

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import androidx.core.content.ContextCompat

private const val DEFAULT_MAX_NOTIFICATION_LOOKBACK_MS = 15 * 60 * 1000L
private const val DEFAULT_MAX_NOTIFICATION_COUNT = 5
private const val DEFAULT_MAX_CONTACT_SUMMARY_COUNT = 5
private const val DEFAULT_MAX_CONTACT_SUMMARY_LOOKBACK = 20
private const val DEFAULT_MAX_RECENT_FILE_COUNT = 5
private const val MAX_RECENT_FILE_COUNT = 50

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
    data class PermissionDenied(val reason: String) : RecentFileReadResult()
    data class Failed(val reason: String) : RecentFileReadResult()
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

class AndroidForegroundAppProvider(
    private val context: Context,
) : ForegroundAppProvider {
    private val usageStatsManager: UsageStatsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } else {
            null
        }
    private val packageManager = context.packageManager

    override fun currentForegroundApp(): ForegroundAppReadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || usageStatsManager == null) {
            return ForegroundAppReadResult.Failed(
                "当前系统版本不支持查询前台应用",
            )
        }
        if (!hasUsageStatsPermission()) {
            return ForegroundAppReadResult.PermissionDenied("未授权“查看应用使用情况”权限")
        }
        val now = System.currentTimeMillis()
        val usages = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - DEFAULT_MAX_NOTIFICATION_LOOKBACK_MS,
            now,
        ) ?: emptyList()
        if (usages.isEmpty()) {
            return ForegroundAppReadResult.Failed("未能查询到应用使用统计")
        }
        val current = usages.maxByOrNull { it.lastTimeUsed } ?: return ForegroundAppReadResult.Failed(
            "未能识别当前应用",
        )
        if (current.packageName.isBlank()) {
            return ForegroundAppReadResult.Failed("当前前台应用包名为空")
        }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(current.packageName, 0)).toString()
        }.getOrNull() ?: current.packageName
        return ForegroundAppReadResult.Available(
            ForegroundAppInfo(
                packageName = current.packageName,
                appLabel = appLabel,
                lastTimeUsedMillis = current.lastTimeUsed,
            ),
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
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
            return RecentFileReadResult.PermissionDenied(permissionMessageForKind(normalizedKind))
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

            val files = mutableListOf<RecentFileItem>()
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeTypeIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val modifiedIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val mimeType = it.getString(mimeTypeIndex).orEmpty()
                    val kindFromMime = normalizedKindFromMimeType(mimeType)
                    if (filter.allowedKind != KIND_ALL && kindFromMime != filter.allowedKind) continue

                    files += RecentFileItem(
                        id = it.getLong(idIndex),
                        name = it.getString(nameIndex).orEmpty().ifBlank { "未命名文件" },
                        mimeType = mimeType.ifBlank { "application/octet-stream" },
                        kind = filter.outputKind ?: kindFromMime,
                        sizeBytes = it.getLong(sizeIndex).coerceAtLeast(0L),
                        lastModifiedMillis = it.getLong(modifiedIndex).coerceAtLeast(0L) * 1000L,
                    )
                }
            }

            RecentFileReadResult.Available(files.take(normalizedMaxCount))
        } catch (_: SecurityException) {
            RecentFileReadResult.PermissionDenied("未授权“读取文件”权限")
        } catch (throwable: Throwable) {
            RecentFileReadResult.Failed(
                throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName,
            )
        }
    }

    private fun buildKindFilter(kind: String): KindFilter =
        when (kind) {
            KIND_ALL ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaFilterForGrantedPermissions()
                } else {
                    KindFilter(allowedKind = KIND_ALL)
                }

            KIND_IMAGES -> KindFilter(
                allowedKind = KIND_IMAGES,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("image/%"),
            )

            KIND_SCREENSHOTS -> KindFilter(
                allowedKind = KIND_IMAGES,
                outputKind = KIND_SCREENSHOTS,
                selection = screenshotSelection(),
                selectionArgs = screenshotSelectionArgs(),
            )

            KIND_VIDEOS -> KindFilter(
                allowedKind = KIND_VIDEOS,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("video/%"),
            )

            KIND_AUDIO -> KindFilter(
                allowedKind = KIND_AUDIO,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("audio/%"),
            )

            KIND_DOCUMENTS -> KindFilter(
                allowedKind = KIND_DOCUMENTS,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                selectionArgs = arrayOf("application/%", "text/%", "application/x-%"),
            )

            KIND_DOWNLOADS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    KindFilter(
                        allowedKind = KIND_ALL,
                        selection = "LOWER(${MediaStore.Files.FileColumns.RELATIVE_PATH}) LIKE ?",
                        selectionArgs = arrayOf("%download%"),
                    )
                } else {
                    KindFilter(allowedKind = KIND_ALL)
                }

            KIND_OTHERS -> KindFilter(
                allowedKind = KIND_OTHERS,
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IS NULL",
            )

            else -> KindFilter(allowedKind = KIND_ALL)
        }

    private fun mediaFilterForGrantedPermissions(): KindFilter {
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
        return KindFilter(
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

    private fun normalizedKindFromMimeType(mimeType: String): String {
        val normalized = mimeType.lowercase()
        return when {
            normalized.startsWith("image/") -> KIND_IMAGES
            normalized.startsWith("video/") -> KIND_VIDEOS
            normalized.startsWith("audio/") -> KIND_AUDIO
            normalized.startsWith("application/") || normalized.startsWith("text/") -> KIND_DOCUMENTS
            else -> KIND_OTHERS
        }
    }

    private data class KindFilter(
        val allowedKind: String,
        val outputKind: String? = null,
        val selection: String? = null,
        val selectionArgs: Array<String>? = null,
    )

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
