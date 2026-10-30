package com.bytedance.zgx.solin.device

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [MediaReadAccessPolicy] to the media permission semantics it was extracted from.
 *
 * [LegacyOracle] is a verbatim copy of the pre-extraction private logic from
 * `AndroidRecentFileProvider` / `AndroidRecentImageTextProvider`, with `Build.VERSION.SDK_INT`
 * replaced by a parameter. Every SDK level from minSdk 28 to 35 and every subset of the relevant
 * permissions must produce identical decisions.
 */
class MediaReadAccessPolicyTest {
    private val permissions = listOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    private val kinds = listOf(
        "all", "screenshots", "images", "videos", "audio", "documents", "downloads", "others", "unknown",
    )

    @Test
    fun policyMatchesLegacyDecisionsForEverySdkAndPermissionSubset() {
        var cases = 0
        for (sdk in 28..35) {
            for (mask in 0 until (1 shl permissions.size)) {
                val granted = permissions.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
                val check: (String) -> Boolean = { it in granted }
                val policy = MediaReadAccessPolicy(sdkInt = sdk, hasPermission = check)
                val oracle = LegacyOracle(sdk, check)
                val label = "sdk=$sdk granted=$granted"
                for (kind in kinds) {
                    assertEquals("$label read $kind", oracle.hasReadPermissionForKind(kind), policy.hasReadPermissionForKind(kind))
                    assertEquals("$label message $kind", oracle.permissionMessageForKind(kind), policy.permissionMessageForKind(kind))
                    assertEquals("$label retryable $kind", oracle.isRuntimePermissionRetryableKind(kind), policy.isRuntimePermissionRetryableKind(kind))
                    assertEquals("$label scope $kind", oracle.mediaAccessScopeForKind(kind), policy.mediaAccessScopeForKind(kind))
                    assertEquals("$label visual $kind", oracle.hasVisualMediaAccessForKind(kind), policy.hasVisualMediaAccessForKind(kind))
                    cases++
                }
                assertEquals("$label image read", oracle.hasImageReadPermission(), policy.hasImageReadPermission())
                assertEquals("$label image scope", oracle.mediaAccessScopeForImages(), policy.mediaAccessScopeForImages())
                assertEquals("$label audio", check(Manifest.permission.READ_MEDIA_AUDIO), policy.hasAudioPermission())
            }
        }
        assertEquals(8 * 32 * kinds.size, cases)
    }

    @Test
    fun partialPhotoAccessOnApi34IsReportedAsUserSelected() {
        val policy = MediaReadAccessPolicy(sdkInt = 34) { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }

        assertTrue(policy.hasReadPermissionForKind("images"))
        assertEquals(MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA, policy.mediaAccessScopeForKind("images"))
        assertEquals(MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA, policy.mediaAccessScopeForImages())
        assertFalse(policy.hasReadPermissionForKind("audio"))
    }

    @Test
    fun nonMediaKindsFailClosedOnApi33WithPickerGuidance() {
        val policy = MediaReadAccessPolicy(sdkInt = 33) { true }

        assertFalse(policy.hasReadPermissionForKind("documents"))
        assertFalse(policy.isRuntimePermissionRetryableKind("documents"))
        assertTrue(policy.permissionMessageForKind("documents").contains("文件选择器"))
    }

    /** Verbatim pre-extraction logic; SDK_INT replaced by [sdk]. Do not "fix" this copy. */
    private class LegacyOracle(
        private val sdk: Int,
        private val hasPermission: (String) -> Boolean,
    ) {
        fun hasReadPermissionForKind(kind: String): Boolean {
            if (sdk >= 34) {
                return when (kind) {
                    "screenshots" -> hasVisualMediaAccessForKind("images")
                    "images" -> hasVisualMediaAccessForKind("images")
                    "videos" -> hasVisualMediaAccessForKind("videos")
                    "audio" -> hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    "all" -> hasVisualMediaAccessForKind("images") ||
                        hasVisualMediaAccessForKind("videos") ||
                        hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    else -> false
                }
            }
            if (sdk >= 33) {
                return when (kind) {
                    "screenshots" -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    "images" -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    "videos" -> hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    "audio" -> hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    "all" -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                        hasPermission(Manifest.permission.READ_MEDIA_VIDEO) ||
                        hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    else -> false
                }
            }
            return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun permissionMessageForKind(kind: String): String =
            if (sdk >= 33 && kind in setOf("documents", "downloads", "others")) {
                "当前 Android 版本需要通过系统文件选择器授权非媒体文件"
            } else {
                "未授权“读取文件”权限"
            }

        fun isRuntimePermissionRetryableKind(kind: String): Boolean =
            sdk < 33 || kind !in setOf("documents", "downloads", "others")

        fun mediaAccessScopeForKind(kind: String): String {
            if (sdk < 33) {
                return MEDIA_ACCESS_SCOPE_LEGACY_STORAGE
            }
            if (sdk >= 34) {
                if (kind in setOf("screenshots", "images")) {
                    return visualMediaAccessScope(Manifest.permission.READ_MEDIA_IMAGES)
                }
                if (kind == "videos") {
                    return visualMediaAccessScope(Manifest.permission.READ_MEDIA_VIDEO)
                }
                if (kind == "all" &&
                    hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
                    !hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                    !hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                ) {
                    return MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA
                }
            }
            return MEDIA_ACCESS_SCOPE_GRANTED_MEDIA_ONLY
        }

        private fun visualMediaAccessScope(fullMediaPermission: String): String =
            if (hasPermission(fullMediaPermission)) {
                MEDIA_ACCESS_SCOPE_FULL_VISUAL_MEDIA
            } else if (sdk >= 34 &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) {
                MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA
            } else {
                MEDIA_ACCESS_SCOPE_GRANTED_MEDIA_ONLY
            }

        fun hasVisualMediaAccessForKind(kind: String): Boolean {
            val fullMediaPermission = when (kind) {
                "images", "screenshots" -> Manifest.permission.READ_MEDIA_IMAGES
                "videos" -> Manifest.permission.READ_MEDIA_VIDEO
                else -> return false
            }
            return hasPermission(fullMediaPermission) ||
                (sdk >= 34 &&
                    hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }

        fun hasImageReadPermission(): Boolean =
            if (sdk >= 34) {
                hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else if (sdk >= 33) {
                hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

        fun mediaAccessScopeForImages(): String =
            if (sdk < 33) {
                MEDIA_ACCESS_SCOPE_LEGACY_STORAGE
            } else if (sdk >= 34 &&
                !hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) {
                MEDIA_ACCESS_SCOPE_USER_SELECTED_VISUAL_MEDIA
            } else if (sdk >= 33) {
                MEDIA_ACCESS_SCOPE_FULL_VISUAL_MEDIA
            } else {
                MEDIA_ACCESS_SCOPE_GRANTED_MEDIA_ONLY
            }
    }
}
