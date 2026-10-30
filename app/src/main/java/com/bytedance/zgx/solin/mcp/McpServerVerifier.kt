package com.bytedance.zgx.solin.mcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Peer verification for Binder MCP servers. A server is trusted only when its package holds
 * Solin's `MCP_SERVER` signature permission (same signing certificate) and it exports a service
 * for the MCP action at exactly the registered component. Any lookup failure fails closed.
 *
 * Note: on Android 11+ package visibility also applies; server packages must be visible to Solin
 * (a `<queries>` entry for [ACTION_MCP_SERVER]) or verification fails closed.
 */
object McpServerVerifier {
    const val MCP_SERVER_PERMISSION = "com.bytedance.zgx.solin.permission.MCP_SERVER"
    const val ACTION_MCP_SERVER = "com.bytedance.zgx.solin.MCP_SERVER"

    fun isTrustedServer(context: Context, component: ComponentName): Boolean =
        runCatching {
            val packageManager = context.packageManager
            if (component.packageName == context.packageName) return@runCatching false
            val holdsPermission = packageManager.checkPermission(
                MCP_SERVER_PERMISSION,
                component.packageName,
            ) == PackageManager.PERMISSION_GRANTED
            if (!holdsPermission) return@runCatching false
            val intent = Intent(ACTION_MCP_SERVER).setComponent(component)
            packageManager.queryIntentServices(intent, 0).any { info ->
                info.serviceInfo?.packageName == component.packageName &&
                    info.serviceInfo?.name == component.className &&
                    info.serviceInfo?.exported == true
            }
        }.getOrDefault(false)

    /** UID of the server package, used to authenticate Binder replies; null fails closed. */
    fun serverUid(context: Context, component: ComponentName): Int? =
        runCatching { context.packageManager.getPackageUid(component.packageName, 0) }.getOrNull()
}
