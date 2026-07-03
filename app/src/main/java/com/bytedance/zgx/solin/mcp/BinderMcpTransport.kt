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
import org.json.JSONArray
import org.json.JSONObject
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
 */
class BinderMcpTransport(
    private val context: Context,
    private val component: ComponentName,
    private val requestTimeoutMs: Long = McpProtocol.DEFAULT_REQUEST_TIMEOUT_MS,
) : McpTransport {

    override val serverId: String = component.flattenToShortString()

    private val nextId = AtomicLong(1)
    private val stateLock = Any()

    @Volatile private var replyMessenger: Messenger? = null
    @Volatile private var service: Messenger? = null
    @Volatile private var bound = false

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
            val json = msg.data?.getString("json") ?: return
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
            parseResponse(json)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse response from $serverId: $json", t)
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
    suspend fun connect(): Boolean {
        if (bound) return true
        connectMutex.withLock {
            if (bound) return true
            val deferred = CompletableDeferred<Boolean>()
            connectDeferred = deferred
            val intent = Intent(ACTION_MCP_SERVER).setComponent(component)
            val bindOk = try {
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (t: SecurityException) {
                Log.w(TAG, "bind denied for $serverId", t)
                false
            }
            if (!bindOk) {
                connectDeferred = null
                return false
            }
            return try {
                withTimeout(requestTimeoutMs) { deferred.await() }
            } catch (t: TimeoutCancellationException) {
                Log.w(TAG, "bind timed out for $serverId")
                runCatching { context.unbindService(conn) }
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
            sendJson(serializeRequest(request), waitForReply = true)
            withTimeout(requestTimeoutMs) { def.await() }
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
        sendJson(serializeRequest(notif), waitForReply = false)
    }

    private fun sendJson(json: String, waitForReply: Boolean) {
        val svc = service ?: throw RemoteException("MCP service not bound: $serverId")
        val msg = Message.obtain(null, if (waitForReply) MSG_REQUEST else MSG_REQUEST)
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
            if (bound) {
                bound = false
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
        private const val ACTION_MCP_SERVER = "com.bytedance.zgx.solin.MCP_SERVER"

        private fun serializeRequest(r: McpRequest): String = JSONObject().apply {
            put("jsonrpc", r.jsonrpc)
            put("id", r.id)
            put("method", r.method)
            r.params?.let { put("params", JSONObject(it)) }
        }.toString()

        private fun parseResponse(json: String): McpResponse {
            val o = JSONObject(json)
            val id = o.optLong("id", -1)
            val err = if (o.has("error")) {
                val e = o.getJSONObject("error")
                McpError(e.getInt("code"), e.getString("message"), e.opt("data"))
            } else {
                null
            }
            val result: Map<String, Any?>? = if (o.has("result")) {
                jsonObjectToMap(o.getJSONObject("result"))
            } else {
                null
            }
            return McpResponse(id = id, result = result, error = err)
        }

        /** Recursively convert a [JSONObject] into a plain Kotlin [Map] so callers
         *  can rely on `as? Map<*,*>` / `as? List<*>` casts working on nested values. */
        private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
            val out = HashMap<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = unwrapJsonValue(obj.get(k))
            }
            return out
        }

        private fun jsonArrayToList(arr: JSONArray): List<Any?> {
            val out = ArrayList<Any?>(arr.length())
            for (i in 0 until arr.length()) {
                out += unwrapJsonValue(arr.get(i))
            }
            return out
        }

        private fun unwrapJsonValue(v: Any?): Any? = when (v) {
            is JSONObject -> jsonObjectToMap(v)
            is JSONArray -> jsonArrayToList(v)
            JSONObject.NULL -> null
            else -> v
        }
    }
}
