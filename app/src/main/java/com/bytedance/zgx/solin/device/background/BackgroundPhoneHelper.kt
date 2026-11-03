/*
 * Copyright 2026 wangzan101; modifications Copyright 2026 Solin contributors.
 * Licensed under the Apache License, Version 2.0.
 * Adapted from Mika 0091d2d67b541a417df913a23fefbb8e3a04e4a7 AgentDisplay,
 * ShellService.displayFlags and ShellContext (itself informed by Apache-2.0 scrcpy).
 * Changes: authenticated local-only debug protocol, no input/shell/unlock operations,
 * no ALWAYS_UNLOCKED/SECURE flags, no untrusted-display fallback, bounded lifecycle.
 */
package com.bytedance.zgx.solin.device.background

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.graphics.Point
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Timer
import java.util.TimerTask

/** Entry point for an explicitly user-started adb app_process, never an Android exported service. */
object BackgroundPhoneHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Process.myUid() == 2000) { "Must be started by authorized ADB shell" }
        check(Build.VERSION.SDK_INT >= 31) { "Android 12 required" }
        require(args.size == 1)
        val appUid = args[0].toInt().also { require(it >= 10_000) }
        // Read the credential from stdin, never expose it in process arguments or logs (T8).
        val bootstrap = System.`in`.bufferedReader()
        val token = bootstrap.readLine().also { require(it != null && BackgroundPhoneProtocol.validToken(it)) }!!
        val expires = bootstrap.readLine().toLong()
        require(expires > System.currentTimeMillis() && expires - System.currentTimeMillis() <= BackgroundPhoneProtocol.MAX_LIFETIME_MS)
        Looper.prepareMainLooper()
        val context = ShellIdentity.create()
        val helper = Helper(context, appUid, token, expires)
        Thread({
            try { helper.serve() } catch (_: Exception) {
                // Transport closure/expiry is terminal; never print framework or protocol content.
            } finally {
                helper.close()
                // Android's main Looper cannot quit; this standalone shell process has no other owner.
                kotlin.system.exitProcess(0)
            }
        }, "solin-background-helper").start()
        Looper.loop()
    }

    private class Helper(private val context: Context, private val appUid: Int, private val token: String, private val expires: Long) : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4) }
        private val deadline = SystemClock.elapsedRealtime() + (expires - System.currentTimeMillis()).coerceAtLeast(0)
        private val lock = Any()
        @Volatile private var closed = false
        @Volatile private var connection: Socket? = null
        private var display: VirtualDisplay? = null
        private var reader: ImageReader? = null
        private var width = 0
        private var height = 0
        private var dpi = 0
        private var expectedRotation = 0
        private var launchedPackage: String? = null
        private val framesLock = Any()
        private var latestFrame: Bitmap? = null
        private val frameThread = HandlerThread("SolinBackgroundFrames").apply { start() }
        private val frameHandler = Handler(frameThread.looper)
        private val taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        private val timer = Timer("solin-background-expiry", true)

        init {
            timer.schedule(object : TimerTask() {
                override fun run() {
                    // Expiry/disconnect/lock always destroys the display, including during idle periods.
                    val invalid = SystemClock.elapsedRealtime() >= deadline || locked() ||
                        synchronized(lock) { display != null && runCatching { state(); false }.getOrDefault(true) }
                    if (invalid) close()
                }
            }, 1_000, 1_000)
        }

        fun serve() {
            println("PORT=${server.localPort}")
            System.out.flush()
            while (!closed) {
                val peer = try { server.accept() } catch (_: Exception) { return }
                if (!peer.inetAddress.isLoopbackAddress) { peer.close(); continue }
                connection = peer
                peer.soTimeout = 5_000
                val input = DataInputStream(peer.getInputStream())
                val output = DataOutputStream(peer.getOutputStream())
                // A hard handshake deadline also defeats clients that trickle bytes under SO_TIMEOUT.
                val handshakeDeadline = object : TimerTask() { override fun run() { runCatching { peer.close() } } }
                timer.schedule(handshakeDeadline, 5_000)
                val channel = try {
                    BackgroundPhoneProtocol.serverHandshake(input, output, token, appUid, expires)
                } catch (_: Exception) {
                    peer.close(); connection = null; continue
                } finally { handshakeDeadline.cancel() }
                peer.soTimeout = 0 // Legitimate idle is bounded by the session's overall expiry.
                try {
                    while (!closed) {
                        var frameDeadline: TimerTask? = null
                        val request = JSONObject(channel.receive(4096,
                            frameStarted = {
                                frameDeadline = object : TimerTask() { override fun run() { runCatching { peer.close() } } }
                                timer.schedule(frameDeadline, 5_000)
                            },
                            frameFinished = { frameDeadline?.cancel() },
                        ))
                        val response = try {
                            synchronized(lock) {
                                check(!closed && SystemClock.elapsedRealtime() < deadline && !locked()) { "session_unavailable" }
                                when (request.getString("method")) {
                                    "create" -> create(request)
                                    "status" -> state()
                                    "launch" -> launch(request.getString("package"))
                                    "capture" -> capture()
                                    "stop" -> JSONObject().put("ok", true)
                                    else -> error("unsupported_operation")
                                }
                            }
                        } catch (error: Exception) {
                            // Only allowlisted constant codes, never framework messages/private content.
                            JSONObject().put("ok", false).put("error", BackgroundPhoneProtocol.safeFailure(error.message))
                        }
                        channel.send(response.toString())
                        if (request.optString("method") == "stop" || !response.optBoolean("ok")) return
                    }
                } finally { close() }
                return // One authenticated owner only; reconnect requires new explicit provisioning.
            }
        }

        private fun create(request: JSONObject): JSONObject {
            check(display == null) { "already_created" }
            width = request.getInt("width"); height = request.getInt("height"); dpi = request.getInt("densityDpi")
            BackgroundPhoneProtocol.requireGeometry(width, height, dpi)
            tasks() // Refuse creation when task ownership cannot be inspected.
            val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = images
            images.setOnImageAvailableListener({ source -> drainFrame(source) }, frameHandler)
            // T6: no SECURE/ALWAYS_UNLOCKED flag; no permission downgrade if TRUSTED is refused.
            // Construct with the shell-attributed wrapper. Delegating getSystemService to the
            // system context would use package android with uid 2000 and fail attribution checks.
            val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(context)
            val created = manager.createVirtualDisplay(
                "Solin experimental background", width, height, dpi, images.surface,
                BackgroundDisplayPolicy.flags(Build.VERSION.SDK_INT), null, Handler(Looper.getMainLooper()),
            ) ?: error("display_unavailable")
            display = created
            expectedRotation = created.display.rotation
            check(created.display.displayId > 0) { "main_display_forbidden" }
            return state()
        }

        private fun state(): JSONObject {
            val current = checkNotNull(display) { "not_created" }
            val tasks = tasks()
            // T2: unrelated main-screen use is allowed; never share the user's active target app.
            val id = current.display.displayId
            val size = Point().also { current.display.getRealSize(it) }
            check(size.x == width && size.y == height && current.display.rotation == expectedRotation) { "display_geometry_changed" }
            launchedPackage?.let { target ->
                check(tasks.none { displayId(it) != id &&
                    (it.topActivity?.packageName == target || it.baseActivity?.packageName == target) }) { "app_in_use" }
            }
            val top = tasks.firstOrNull { displayId(it) == id }
            return JSONObject().put("ok", true).put("displayId", current.display.displayId)
                .put("foregroundPackage", top?.topActivity?.packageName.orEmpty())
                .put("width", width).put("height", height).put("densityDpi", dpi)
                .put("isolatedFocus", Build.VERSION.SDK_INT >= 34)
        }

        private fun launch(packageName: String): JSONObject {
            check(Build.VERSION.SDK_INT >= 34) { "independent_focus_unavailable" }
            require(BackgroundPhoneProtocol.validPackage(packageName))
            state()
            val id = checkNotNull(display).display.displayId
            val currentTasks = tasks()
            // Do not steal any existing task from another display, even a task behind the foreground.
            check(currentTasks.none { displayId(it) != id &&
                (it.topActivity?.packageName == packageName || it.baseActivity?.packageName == packageName) }) { "app_in_use" }
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: error("launcher_unavailable")
            check(intent.component?.packageName == packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
            val start = taskManager.javaClass.methods.singleOrNull {
                it.name == "startActivityAsUser" && it.parameterTypes.size == 12
            } ?: error("launch_api_unavailable")
            val result = start.invoke(taskManager, null, "com.android.shell", null, intent,
                null, null, null, 0, 0, null, options, appUid / 100_000) as Int
            check(result >= 0) { "launch_rejected" }
            launchedPackage = packageName
            // A successful launch request is not a verified app outcome. Client must observe freshly.
            return state()
        }

        /** Drain continuously so ImageReader's two slots cannot freeze the display producer. */
        private fun drainFrame(source: ImageReader) {
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
            image.use {
                if (closed) return
                val plane = image.planes.single()
                if (plane.pixelStride != 4 || image.height != height || image.width != width) return
                val strideWidth = plane.rowStride / plane.pixelStride
                synchronized(framesLock) {
                    if (closed) return
                    val buffer = latestFrame?.takeIf { it.width == strideWidth && it.height == height }
                        ?: Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888).also {
                            latestFrame?.recycle(); latestFrame = it
                        }
                    buffer.copyPixelsFromBuffer(plane.buffer.rewind())
                }
            }
        }

        private fun capture(): JSONObject {
            state()
            val cropped = synchronized(framesLock) {
                val frame = checkNotNull(latestFrame) { "frame_unavailable" }
                Bitmap.createBitmap(frame, 0, 0, width, height).let {
                    if (it === frame) checkNotNull(frame.copy(Bitmap.Config.ARGB_8888, false)) else it
                }
            }
            val png = try {
                var visible = false
                for (y in 0 until height step maxOf(1, height / 32)) {
                    for (x in 0 until width step maxOf(1, width / 32)) {
                        if (cropped.getPixel(x, y) and 0x00ffffff != 0) visible = true
                    }
                }
                check(visible) { "blank_frame" }
                ByteArrayOutputStream().use { bytes ->
                    check(cropped.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                    bytes.toByteArray().also { check(it.size <= 5 * 1024 * 1024) }
                }
            } finally { cropped.recycle() }
            state()
            check(!locked() && !closed)
            return JSONObject().put("ok", true).put("png", Base64.encodeToString(png, Base64.NO_WRAP))
        }

        @Suppress("UNCHECKED_CAST")
        private fun tasks(): List<ActivityManager.RunningTaskInfo> {
            val method = taskManager.javaClass.methods.firstOrNull {
                it.name == "getTasks" && it.parameterTypes.toList() == listOf(Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }
            val result = if (method != null) method.invoke(taskManager, 100, false, false, -1) else {
                taskManager.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(taskManager, 100, false, false)
            }
            return result as List<ActivityManager.RunningTaskInfo>
        }

        private fun displayId(task: ActivityManager.RunningTaskInfo): Int =
            Class.forName("android.app.TaskInfo").getField("displayId").getInt(task)

        private fun locked() = runCatching { context.getSystemService(KeyguardManager::class.java).isDeviceLocked }.getOrDefault(true)

        override fun close() {
            if (closed) return
            closed = true
            runCatching { connection?.close() }
            runCatching { server.close() }
            synchronized(lock) {
                runCatching { display?.release() }; display = null
                runCatching { reader?.setOnImageAvailableListener(null, null) }
                runCatching { reader?.close() }; reader = null
                synchronized(framesLock) { latestFrame?.recycle(); latestFrame = null }
                frameThread.quitSafely()
            }
            timer.cancel()
        }
    }

    private class ShellIdentity(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(2000).setPackageName(packageName).build()
        override fun getApplicationContext(): Context = this
        companion object {
            fun create(): Context {
                val type = Class.forName("android.app.ActivityThread")
                val thread = type.getMethod("systemMain").invoke(null)
                return ShellIdentity(type.getMethod("getSystemContext").invoke(thread) as Context)
            }
        }
    }
}

internal object BackgroundDisplayPolicy {
    /** Mika display flag definitions; deliberately excludes secure content and unlocked displays. */
    fun flags(sdk: Int): Int {
        require(sdk >= 31)
        var flags = (1 shl 0) or (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 8)
        if (sdk >= 33) flags = flags or (1 shl 10) or (1 shl 11) or (1 shl 13)
        if (sdk >= 34) flags = flags or (1 shl 14) or (1 shl 16)
        return flags
    }
}
