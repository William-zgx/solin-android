package com.bytedance.zgx.solin.mcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP transport over Android Binder Messenger. Connects to a remote Service
 * that advertises the "com.bytedance.zgx.solin.MCP_SERVER" action and holds
 * the signature permission com.bytedance.zgx.solin.permission.MCP_SERVER.
 *
 * Wire protocol (one JSON message per Messenger Message):
 *   Message.what = MSG_REQUEST  (0) -> request/notification  (data.getString("json"))
 *   Message.what = MSG_RESPONSE (1) -> response              (data.getString("json"))
 *   Message.replyTo = responder Messenger
 *
 * Notifications per JSON-RPC use id=0; requests carry a non-zero monotonically
 * increasing id and expect a matching response.
 *
 * This transport is opt-in; applications must explicitly bind to servers.
 * Default installation binds zero servers.
 *
 * Security hardening:
 * - [connect] binds only after [McpServerVerifier] confirms the server package holds the
 *   MCP_SERVER signature permission and exports the MCP service at this exact component.
 * - Replies are accepted only when `Message.sendingUid` equals the verified server UID, so another
 *   app that obtains the reply Messenger cannot inject responses.
 * - Response payloads are size-capped and never written to logcat (they may contain private data).
 * - Binder is an on-device IPC channel (no network/TLS involved); data sent to the server still
 *   leaves Solin's trust boundary, which callers treat as leaving the device.
 */
class BinderMcpTransport(
    private val context: Context,
    private val component: ComponentName,
    private val requestTimeoutMs: Long = McpProtocol.DEFAULT_REQUEST_TIMEOUT_MS,
) : McpTransport {

    override val serverId: String = component.flattenToShortString()

    private val nextId = AtomicLong(1)
    private val stateLock = Any()

    @Volatile private var expectedServerUid: Int? = null
    @Volatile private var replyMessenger: Messenger? = null
    @Volatile private var service: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var bindRequested = false

    /** Pending in-flight requests keyed by id; guarded by [stateLock]. */
    private val pending = mutableMapOf<Long, CompletableDeferred<McpResponse>>()

    /** Completes once [conn] gets [ServiceConnection.onServiceConnected]. */
    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private val connectMutex = Mutex()

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_RESPONSE) {
                super.handleMessage(msg)
                return
            }
            val expectedUid = expectedServerUid
            if (expectedUid == null || msg.sendingUid != expectedUid) {
                Log.w(TAG, "dropping MCP reply from unexpected uid for $serverId")
                return
            }
            val json = runCatching { msg.data?.getString("json") }.getOrNull() ?: return
            if (json.length > MAX_RESPONSE_CHARS) {
                Log.w(TAG, "dropping oversized MCP reply from $serverId")
                return
            }
            handleIncomingResponse(json)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                synchronized(stateLock) {
                    bound = false
                    connectDeferred?.complete(false)
                    connectDeferred = null
                }
                return
            }
            synchronized(stateLock) {
                service = Messenger(binder)
                replyMessenger = Messenger(incomingHandler)
                bound = true
                connectDeferred?.complete(true)
                connectDeferred = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val toFail: List<CompletableDeferred<McpResponse>>
            synchronized(stateLock) {
                bound = false
                service = null
                replyMessenger = null
                toFail = pending.values.toList()
                pending.clear()
                connectDeferred?.complete(false)
                connectDeferred = null
            }
            toFail.forEach {
                it.completeExceptionally(RemoteException("MCP server disconnected: $serverId"))
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    private fun handleIncomingResponse(json: String) {
        val resp = try {
            McpJsonRpcCodec.parseResponse(json)
        } catch (t: Throwable) {
            // Never log the payload: MCP results may contain private data.
            Log.w(TAG, "failed to parse response from $serverId (${t.javaClass.simpleName})")
            return
        }
        val def = synchronized(stateLock) { pending.remove(resp.id) }
        if (def == null) {
            Log.w(TAG, "received response for unknown/expired id ${resp.id} from $serverId")
            return
        }
        def.complete(resp)
    }

    /**
     * Bind to the remote service. Suspends until [ServiceConnection.onServiceConnected]
     * fires or [requestTimeoutMs] elapses. Safe to call concurrently; only the first
     * caller performs the actual bind.
     */
    override suspend fun connect(): Boolean {
        if (bound) return true
        connectMutex.withLock {
            if (bound) return true
            if (!McpServerVerifier.isTrustedServer(context, component)) {
                Log.w(TAG, "refusing to bind untrusted MCP server $serverId")
                return false
            }
            expectedServerUid = McpServerVerifier.serverUid(context, component) ?: return false
            val deferred = CompletableDeferred<Boolean>()
            connectDeferred = deferred
            val intent = Intent(ACTION_MCP_SERVER).setComponent(component)
            val bindOk = try {
                bindRequested = true
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (t: SecurityException) {
                Log.w(TAG, "bind denied for $serverId")
                false
            }
            if (!bindOk) {
                // bindService may still hold a connection record after returning false.
                runCatching { context.unbindService(conn) }
                bindRequested = false
                connectDeferred = null
                return false
            }
            return try {
                withTimeout(requestTimeoutMs) { deferred.await() }
            } catch (t: TimeoutCancellationException) {
                Log.w(TAG, "bind timed out for $serverId")
                runCatching { context.unbindService(conn) }
                bindRequested = false
                synchronized(stateLock) {
                    bound = false
                    service = null
                    replyMessenger = null
                    connectDeferred = null
                }
                false
            }
        }
    }

    override fun isConnected(): Boolean = bound

    override suspend fun request(request: McpRequest): McpResponse {
        check(bound) { "not connected to $serverId" }
        require(request.id != 0L) { "request id must be non-zero; use notify() for notifications" }
        val def = CompletableDeferred<McpResponse>()
        synchronized(stateLock) {
            pending[request.id] = def
        }
        return try {
            sendJson(McpJsonRpcCodec.serialize(request), waitForReply = true)
            withTimeout(requestTimeoutMs) { def.await() }
        } catch (timeout: TimeoutCancellationException) {
            synchronized(stateLock) { pending.remove(request.id) }
            def.cancel()
            // A server timeout is a tool failure, not a cancellation of the caller's agent run.
            throw java.io.IOException("MCP request timed out: ${request.method}")
        } catch (t: Throwable) {
            synchronized(stateLock) { pending.remove(request.id) }
            def.completeExceptionally(t)
            throw t
        }
    }

    override suspend fun notify(method: String, params: Map<String, Any?>?) {
        if (!bound) {
            Log.w(TAG, "dropping notification $method; not connected to $serverId")
            return
        }
        // JSON-RPC notifications carry id=0 and receive no response.
        val notif = McpRequest(id = 0, method = method, params = params)
        sendJson(McpJsonRpcCodec.serialize(notif), waitForReply = false)
    }

    private fun sendJson(json: String, waitForReply: Boolean) {
        val svc = service ?: throw RemoteException("MCP service not bound: $serverId")
        val msg = Message.obtain(null, MSG_REQUEST)
        msg.data = Bundle().apply { putString("json", json) }
        if (waitForReply) {
            msg.replyTo = replyMessenger
                ?: throw RemoteException("MCP reply messenger not ready: $serverId")
        }
        svc.send(msg)
    }

    override fun close() {
        val toCancel: List<CompletableDeferred<McpResponse>>
        synchronized(stateLock) {
            toCancel = pending.values.toList()
            pending.clear()
            if (bound || bindRequested) {
                bound = false
                bindRequested = false
                runCatching { context.unbindService(conn) }
            }
            service = null
            replyMessenger = null
            connectDeferred?.complete(false)
            connectDeferred = null
        }
        toCancel.forEach { it.cancel() }
    }

    /** Allocate the next request id for callers that don't supply one. */
    fun newRequestId(): Long = nextId.getAndIncrement()

    companion object {
        private const val TAG = "BinderMcpTransport"
        private const val MSG_REQUEST = 0
        private const val MSG_RESPONSE = 1
        private const val ACTION_MCP_SERVER = McpServerVerifier.ACTION_MCP_SERVER

        /** Upper bound for one JSON reply; larger replies are dropped (request then times out). */
        private const val MAX_RESPONSE_CHARS = 512 * 1024
    }
}
