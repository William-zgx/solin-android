package com.bytedance.zgx.solin.device

import android.Manifest
import android.os.Build

private const val MEDIA_KIND_ALL = "all"
private const val MEDIA_KIND_SCREENSHOTS = "screenshots"
private const val MEDIA_KIND_IMAGES = "images"
private const val MEDIA_KIND_VIDEOS = "videos"
private const val MEDIA_KIND_AUDIO = "audio"
private const val MEDIA_KIND_DOCUMENTS = "documents"
private const val MEDIA_KIND_DOWNLOADS = "downloads"
private const val MEDIA_KIND_OTHERS = "others"
private val NON_MEDIA_KINDS = setOf(MEDIA_KIND_DOCUMENTS, MEDIA_KIND_DOWNLOADS, MEDIA_KIND_OTHERS)

/**
 * Media read permission and access-scope decisions for the recent-file and recent-image-OCR
 * providers, shared so both apply the same SDK-dependent rules:
 *
 * - API < 33: legacy `READ_EXTERNAL_STORAGE`.
 * - API 33: granular `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO`; non-media kinds need the system
 *   file picker instead (not retryable as a runtime permission).
 * - API 34+: additionally `READ_MEDIA_VISUAL_USER_SELECTED` (partial photo access), reported as the
 *   `user_selected_visual_media` scope.
 *
 * Pure apart from [hasPermission], with the SDK level injected, so the matrix is JVM-testable. The
 * rules are a behaviour-preserving extraction; do not relax them without a privacy review.
 */
internal class MediaReadAccessPolicy(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val hasPermission: (String) -> Boolean,
) {
    fun hasReadPermissionForKind(kind: String): Boolean {
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return when (kind) {
                MEDIA_KIND_SCREENSHOTS -> hasVisualMediaAccessForKind(MEDIA_KIND_IMAGES)
                MEDIA_KIND_IMAGES -> hasVisualMediaAccessForKind(MEDIA_KIND_IMAGES)
                MEDIA_KIND_VIDEOS -> hasVisualMediaAccessForKind(MEDIA_KIND_VIDEOS)
                MEDIA_KIND_AUDIO -> hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                MEDIA_KIND_ALL -> hasVisualMediaAccessForKind(MEDIA_KIND_IMAGES) ||
                    hasVisualMediaAccessForKind(MEDIA_KIND_VIDEOS) ||
                    hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                else -> false
            }
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            return when (kind) {
                MEDIA_KIND_SCREENSHOTS -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                MEDIA_KIND_IMAGES -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                MEDIA_KIND_VIDEOS -> hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                MEDIA_KIND_AUDIO -> hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                MEDIA_KIND_ALL -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(Manifest.permission.READ_MEDIA_VIDEO) ||
                    hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                else -> false
            }
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun permissionMessageForKind(kind: String): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && kind in NON_MEDIA_KINDS) {
            "当前 Android 版本需要通过系统文件选择器授权非媒体文件"
        } else {
            "未授权“读取文件”权限"
        }

    fun isRuntimePermissionRetryableKind(kind: String): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || kind !in NON_MEDIA_KINDS

    fun mediaAccessScopeForKind(kind: String): String {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return MEDIA_ACCESS_SCOPE_LEGACY_STORAGE
        }
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (kind == MEDIA_KIND_SCREENSHOTS || kind == MEDIA_KIND_IMAGES) {
                return visualMediaAccessScope(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (kind == MEDIA_KIND_VIDEOS) {
                return visualMediaAccessScope(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (kind == MEDIA_KIND_ALL &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
                !hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                !hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
            ) {
                return MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA
            }
        }
        return MEDIA_ACCESS_SCOPE_GRANTED_MEDIA_ONLY
    }

    /** Full or user-selected visual access for an images/screenshots/videos kind; false for others. */
    fun hasVisualMediaAccessForKind(kind: String): Boolean {
        val fullMediaPermission = when (kind) {
            MEDIA_KIND_IMAGES, MEDIA_KIND_SCREENSHOTS -> Manifest.permission.READ_MEDIA_IMAGES
            MEDIA_KIND_VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
            else -> return false
        }
        return hasPermission(fullMediaPermission) || hasUserSelectedVisualMedia()
    }

    fun hasAudioPermission(): Boolean =
        hasPermission(Manifest.permission.READ_MEDIA_AUDIO)

    /** Image read access for the recent-image OCR path. */
    fun hasImageReadPermission(): Boolean =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Access scope reported for the recent-image OCR path. */
    fun mediaAccessScopeForImages(): String =
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            MEDIA_ACCESS_SCOPE_LEGACY_STORAGE
        } else if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
            hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        ) {
            MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA
        } else {
            MEDIA_ACCESS_SCOPE_FULL_VISUAL_MEDIA
        }

    private fun visualMediaAccessScope(fullMediaPermission: String): String =
        if (hasPermission(fullMediaPermission)) {
            MEDIA_ACCESS_SCOPE_FULL_VISUAL_MEDIA
        } else if (hasUserSelectedVisualMedia()) {
            MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA
        } else {
            MEDIA_ACCESS_SCOPE_GRANTED_MEDIA_ONLY
        }

    private fun hasUserSelectedVisualMedia(): Boolean =
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
}
