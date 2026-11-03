/*
 * Copyright 2026 wangzan101; modifications Copyright 2026 Solin contributors.
 * Licensed under the Apache License, Version 2.0.
 * Adapted from Mika 0091d2d67b541a417df913a23fefbb8e3a04e4a7 display/session concepts.
 * Changes: explicit debug opt-in, expiring token-authenticated loopback transport, no input injection,
 * no secure-screen capture, no shell commands and no implicit remote egress.
 */
package com.bytedance.zgx.solin.device.background

import android.content.Context
import android.content.pm.ApplicationInfo
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import android.os.Process
import android.util.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Only fixed diagnostic codes cross the debug UI boundary, never framework exception messages. */
class BackgroundPhoneFailure(val code: String) : IllegalStateException(code)

/** Metadata only. Captured pixels remain LocalOnly and require the existing egress gate. */
data class BackgroundPhoneState(
    val displayId: Int,
    val foregroundPackage: String?,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val isolatedFocus: Boolean,
)

/**
 * Explicitly enabled development session, provisioned by start_background_phone_helper.sh.
 * It cannot inject gestures or type: those must still use the live action gate on this display.
 * A failed/expired session never falls back to the main display (T2/T3).
 */
class BackgroundPhoneSession private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val expiresAtMs: Long,
) : AutoCloseable {
    @Volatile private var closed = false
    private lateinit var channel: BackgroundPhoneProtocol.Channel

    /** Bound to an explicit caller scope, then propagated to the service worker. Never a global mode. */
    class Binding internal constructor(private val session: BackgroundPhoneSession, val initial: BackgroundPhoneState) {
        private var expectedPackage: String? = initial.foregroundPackage
        fun launch(packageName: String): BackgroundPhoneState {
            requireActive()
            return session.launch(packageName).also {
                check(it.displayId == initial.displayId) { "Background display changed" }
                expectedPackage = it.foregroundPackage
            }
        }
        fun requireActive(): BackgroundPhoneState {
            check(!session.closed && System.currentTimeMillis() < session.expiresAtMs) { "Background session expired" }
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Background IPC must not block the UI thread" }
            return session.status().also {
                check(it.displayId == initial.displayId && it.width == initial.width && it.height == initial.height) { "Background display changed" }
                check(it.foregroundPackage == expectedPackage) { "Background app changed during the operation" }
            }
        }
        fun requireLocallyActive() {
            check(!session.closed && System.currentTimeMillis() < session.expiresAtMs) { "Background session expired" }
        }
    }

    /** Run from a worker thread. Ordinary providers/chat outside this scope always use the main screen. */
    fun <T> withControl(block: () -> T): T {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Use a worker thread" }
        check(controlBinding.get() == null) { "Nested background session" }
        return withBinding(Binding(this, status()), block)
    }

    @Synchronized
    fun create(width: Int, height: Int, densityDpi: Int): BackgroundPhoneState {
        BackgroundPhoneProtocol.requireGeometry(width, height, densityDpi)
        return state(call(JSONObject().put("method", "create").put("width", width)
            .put("height", height).put("densityDpi", densityDpi)))
    }

    @Synchronized
    fun status(): BackgroundPhoneState = state(call(JSONObject().put("method", "status")))

    /** Only resolves an installed launcher activity; no URI, extras, commands or component input. */
    @Synchronized
    fun launch(packageName: String): BackgroundPhoneState {
        require(BackgroundPhoneProtocol.validPackage(packageName)) { "Invalid package name" }
        return state(call(JSONObject().put("method", "launch").put("package", packageName)))
    }

    /** App-private in-memory PNG; never persists, logs, uploads or relaxes FLAG_SECURE. */
    @Synchronized
    fun capture(): ByteArray = Base64.decode(call(JSONObject().put("method", "capture")).getString("png"), Base64.NO_WRAP)

    private fun call(request: JSONObject): JSONObject {
        return try {
            check(!closed && System.currentTimeMillis() < expiresAtMs) { "Background session expired or closed" }
            channel.send(request.toString())
            val response = JSONObject(channel.receive())
            if (!response.optBoolean("ok")) throw BackgroundPhoneFailure(
                BackgroundPhoneProtocol.safeFailure(response.optString("error")),
            )
            response
        } catch (error: Exception) {
            // Outcome may be unknown after a timeout. Close; never replay a launch or any mutation.
            closed = true
            runCatching { socket.close() }
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        runCatching { call(JSONObject().put("method", "stop")) }
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        const val AUTH_FILE = "background-phone-session.json"
        private val controlBinding = ThreadLocal<Binding>()
        private val agentSelection = java.util.concurrent.atomic.AtomicReference<BackgroundPhoneSession?>()
        /** Explicit UI selection only. Caller must capture it once per run, not reselect each step. */
        fun selectForAgent(session: BackgroundPhoneSession?) {
            if (session != null) check(!session.closed && System.currentTimeMillis() < session.expiresAtMs) { "Session expired" }
            agentSelection.set(session)
        }
        // Preserve an expired/closed selection as a tombstone: never silently switch to main.
        fun selectedForAgent(): BackgroundPhoneSession? = agentSelection.get()
        private data class RunSelection(val session: BackgroundPhoneSession?)
        private val runs = mutableMapOf<String, RunSelection>()
        private var runMarkerPreferences: android.content.SharedPreferences? = null
        private val backgroundRunMarkers = mutableSetOf<String>()

        /** Only opaque run IDs survive process death; no credentials, display IDs or task content. */
        @Synchronized fun initialize(context: Context) {
            if (runMarkerPreferences != null) return
            val prefs = context.applicationContext.getSharedPreferences("background_phone_runs", Context.MODE_PRIVATE)
            backgroundRunMarkers.addAll(prefs.getStringSet("background_run_ids", emptySet()).orEmpty())
            runMarkerPreferences = prefs
        }

        @Synchronized fun captureForRun(runId: String) {
            if (runs.containsKey(runId)) return
            // A restored background run cannot be rebound to a newly selected session or main screen.
            check(runId !in backgroundRunMarkers) { "Background run cannot resume after process restart" }
            check(runs.size < 128) { "Too many retained background run bindings" }
            val session = agentSelection.get()
            if (session != null) {
                backgroundRunMarkers.add(runId)
                persistRunMarkers()
            }
            runs[runId] = RunSelection(session)
        }
        @Synchronized fun forRun(runId: String): BackgroundPhoneSession? {
            runs[runId]?.let { return it.session }
            check(runId !in backgroundRunMarkers) { "Background run cannot resume after process restart" }
            // Legacy/restored foreground runs have no background marker; never consult current selection.
            return null
        }
        /** Owner teardown is not task completion: retain its background marker across restart. */
        @Synchronized fun releaseRun(runId: String) { runs.remove(runId) }
        @Synchronized fun forgetRun(runId: String) {
            if (backgroundRunMarkers.remove(runId)) {
                try { persistRunMarkers() } catch (error: Exception) {
                    backgroundRunMarkers.add(runId)
                    throw error
                }
            }
            runs.remove(runId)
        }
        private fun persistRunMarkers() {
            runMarkerPreferences?.let {
                check(it.edit().putStringSet("background_run_ids", backgroundRunMarkers.toSet()).commit()) {
                    "Could not persist background run boundary"
                }
            }
        }
        fun currentBinding(): Binding? = controlBinding.get()
        fun <T> withBinding(binding: Binding?, block: () -> T): T {
            val previous = controlBinding.get()
            if (binding == null) controlBinding.remove() else controlBinding.set(binding)
            return try { block() } finally {
                if (previous == null) controlBinding.remove() else controlBinding.set(previous)
            }
        }

        /** Off unless explicitly enabled, and unavailable to a non-debuggable distribution. */
        fun connect(context: Context, userEnabled: Boolean = false): BackgroundPhoneSession {
            check(userEnabled) { "Background phone control is disabled" }
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Development builds only" }
            val file = File(context.noBackupFilesDir, AUTH_FILE)
            check(file.isFile && file.length() in 1..2048) { "Start the background helper through authorized ADB first" }
            val auth = JSONObject(file.readText())
            val expires = auth.getLong("expiresAtMs")
            val now = System.currentTimeMillis()
            check(expires > now && expires - now <= BackgroundPhoneProtocol.MAX_LIFETIME_MS) { "Background authorization expired" }
            val port = auth.getInt("port")
            check(port in 1024..65535 && auth.getInt("uid") == Process.myUid()) { "Unexpected helper endpoint" }
            val token = auth.getString("token")
            check(BackgroundPhoneProtocol.validToken(token)) { "Invalid background authorization" }
            val socket = Socket()
            var stage = "socket_connect"
            try {
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5_000)
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                stage = "authentication"
                val channel = BackgroundPhoneProtocol.clientHandshake(input, output, token, Process.myUid(), expires)
                // Provisioning is one-shot. A crashed/stopped session requires a new explicit ADB start.
                stage = "authorization_consume"
                check(file.delete())
                return BackgroundPhoneSession(socket, input, output, expires).also { it.channel = channel }
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw BackgroundPhoneFailure(stage)
            }
        }

        private fun state(value: JSONObject): BackgroundPhoneState {
            val id = value.getInt("displayId")
            check(id > 0) { "Background display is unavailable" }
            return BackgroundPhoneState(id, value.optString("foregroundPackage").takeIf { it.isNotBlank() },
                value.getInt("width"), value.getInt("height"), value.getInt("densityDpi"), value.getBoolean("isolatedFocus"))
        }
    }
}

/** Bounded protocol shared by the app and the shell process. No method dispatch through reflection. */
internal object BackgroundPhoneProtocol {
    const val MAX_LIFETIME_MS = 15 * 60 * 1000L
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
    private val failureCodes = setOf("session_unavailable", "already_created", "display_unavailable",
        "main_display_forbidden", "not_created", "display_geometry_changed", "app_in_use", "independent_focus_unavailable",
        "launcher_unavailable", "launch_api_unavailable", "launch_rejected", "frame_unavailable", "blank_frame", "unsupported_operation")
    fun safeFailure(value: String?): String = value?.takeIf { it in failureCodes } ?: "background_operation_refused"
    private fun nonce(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private fun mac(token: String, vararg fields: String): String {
        val digest = Mac.getInstance("HmacSHA256")
        val key = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        digest.init(SecretKeySpec(key, "HmacSHA256"))
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> fields.forEach { field ->
            val value = field.toByteArray(Charsets.UTF_8); out.writeInt(value.size); out.write(value)
        } }
        return digest.doFinal(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun proof(token: String, role: String, client: String, server: String, uid: Int, expires: Long) =
        mac(token, "solin-background-v2", role, client, server, uid.toString(), expires.toString())

    fun clientHandshake(input: DataInputStream, output: DataOutputStream, token: String, uid: Int, expires: Long): Channel {
        require(validToken(token))
        val client = nonce()
        write(output, client)
        val challenge = read(input, 256).split(':')
        check(challenge.size == 2 && validToken(challenge[0])) { "Invalid server challenge" }
        val server = challenge[0]
        check(sameToken(proof(token, "server", client, server, uid, expires), challenge[1])) { "Server authentication failed" }
        write(output, proof(token, "client", client, server, uid, expires))
        return Channel(input, output, token, client, server, uid, expires, true).also {
            check(it.receive(64) == "authenticated") { "Handshake incomplete" }
        }
    }

    fun serverHandshake(input: DataInputStream, output: DataOutputStream, token: String, uid: Int, expires: Long): Channel {
        require(validToken(token))
        check(System.currentTimeMillis() < expires)
        val client = read(input, 128)
        check(validToken(client)) { "Invalid client challenge" }
        val server = nonce()
        write(output, "$server:${proof(token, "server", client, server, uid, expires)}")
        check(sameToken(proof(token, "client", client, server, uid, expires), read(input, 128))) { "Client authentication failed" }
        return Channel(input, output, token, client, server, uid, expires, false).also { it.send("authenticated") }
    }

    /** Every frame is direction-, session- and sequence-bound. Authentication never sends the key. */
    class Channel internal constructor(
        private val input: DataInputStream, private val output: DataOutputStream,
        private val token: String, private val client: String, private val server: String,
        private val uid: Int, private val expires: Long, private val clientSide: Boolean,
    ) {
        private var sent = 0L
        private var received = 0L
        private fun signature(direction: String, sequence: Long, payload: String) =
            mac(token, "solin-background-frame-v2", client, server, uid.toString(), expires.toString(), direction, sequence.toString(), payload)
        fun send(payload: String) {
            check(System.currentTimeMillis() < expires && sent < Long.MAX_VALUE)
            val sequence = sent++
            val tag = signature(if (clientSide) "client" else "server", sequence, payload)
            write(output, "$sequence:$tag:$payload")
        }
        fun receive(
            maximumPayload: Int = MAX_MESSAGE_BYTES - 128,
            frameStarted: () -> Unit = {},
            frameFinished: () -> Unit = {},
        ): String {
            check(System.currentTimeMillis() < expires && received < Long.MAX_VALUE)
            // Waiting for the first byte is legitimate idle; a partially delivered frame is bounded.
            val first = input.read()
            if (first < 0) throw java.io.EOFException()
            val frame = try {
                frameStarted()
                val size = (first shl 24) or (input.readUnsignedByte() shl 16) or
                    (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                require(size in 1..minOf(MAX_MESSAGE_BYTES, maximumPayload + 128))
                ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
            } finally { frameFinished() }
            val parts = frame.split(':', limit = 3)
            check(parts.size == 3 && parts[0] == received.toString()) { "Replay or invalid frame sequence" }
            check(parts[2].toByteArray(Charsets.UTF_8).size <= maximumPayload)
            check(sameToken(signature(if (clientSide) "server" else "client", received, parts[2]), parts[1])) { "Frame authentication failed" }
            received++
            return parts[2]
        }
    }
    fun validToken(token: String) = token.matches(Regex("[0-9a-f]{64}"))
    fun validPackage(name: String) = name.length <= 200 && name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
    fun sameToken(expected: String, supplied: String) = validToken(supplied) &&
        MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))
    fun requireGeometry(width: Int, height: Int, dpi: Int) {
        require(width in 240..1440 && height in 240..2560 && dpi in 120..640) { "Unsupported display geometry" }
        require(width.toLong() * height <= 3_686_400) { "Display exceeds pixel budget" }
    }
    fun read(input: DataInputStream, maximum: Int = MAX_MESSAGE_BYTES): String {
        val size = input.readInt()
        require(size in 1..maximum) { "Invalid protocol frame length" }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
    fun write(output: DataOutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_MESSAGE_BYTES) { "Protocol response too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
