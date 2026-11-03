package com.bytedance.zgx.solin.device.background

import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BackgroundPhoneAuthenticationTest {
    private val key = "ab".repeat(32)
    private val clientNonce = "01".repeat(32)
    private val serverNonce = "02".repeat(32)
    private val expiry get() = System.currentTimeMillis() + 60_000L

    @Test fun mutualHandshakeAndAuthenticatedRoundTrip() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val expires = expiry
            val server = executor.submit<String> {
                listener.accept().use { socket ->
                    socket.soTimeout = 2000
                    val channel = BackgroundPhoneProtocol.serverHandshake(DataInputStream(socket.getInputStream()),
                        DataOutputStream(socket.getOutputStream()), key, 10123, expires)
                    val request = channel.receive(100)
                    channel.send("reply:$request")
                    request
                }
            }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 2000
                val channel = BackgroundPhoneProtocol.clientHandshake(DataInputStream(socket.getInputStream()),
                    DataOutputStream(socket.getOutputStream()), key, 10123, expires)
                channel.send("中文:hello")
                assertEquals("reply:中文:hello", channel.receive())
            }
            assertEquals("中文:hello", server.get(3, TimeUnit.SECONDS))
        }
        executor.shutdownNow()
    }

    @Test fun fakeServerProofRejectedBeforeClientSendsProofOrKey() {
        val inputBytes = ByteArrayOutputStream()
        BackgroundPhoneProtocol.write(DataOutputStream(inputBytes), "$serverNonce:${"00".repeat(32)}")
        val output = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            BackgroundPhoneProtocol.clientHandshake(DataInputStream(ByteArrayInputStream(inputBytes.toByteArray())),
                DataOutputStream(output), key, 10123, expiry)
        }
        val sent = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        assertTrue(BackgroundPhoneProtocol.validToken(BackgroundPhoneProtocol.read(sent)))
        assertEquals(0, sent.available()) // Only a random public challenge was disclosed.
        assertFalse(output.toString("UTF-8").contains(key))
    }

    @Test fun idleBeforeFirstByteDoesNotStartFrameDeadline() {
        val expires = expiry
        val bytes = encoded("status", expires)
        var started = false
        var finished = false
        var reads = 0
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(): Int {
                if (reads++ == 0) assertFalse(started) else assertTrue(started)
                return super.read()
            }
        }
        val channel = BackgroundPhoneProtocol.Channel(DataInputStream(source), DataOutputStream(ByteArrayOutputStream()),
            key, clientNonce, serverNonce, 10123, expires, false)
        assertEquals("status", channel.receive(100, { started = true }, { finished = true }))
        assertTrue(started)
        assertTrue(finished)
    }

    @Test fun partialFrameFailureCancelsItsDeadline() {
        var started = false
        var finished = false
        val channel = BackgroundPhoneProtocol.Channel(DataInputStream(ByteArrayInputStream(byteArrayOf(0))),
            DataOutputStream(ByteArrayOutputStream()), key, clientNonce, serverNonce, 10123, expiry, false)
        assertThrows(EOFException::class.java) {
            channel.receive(100, { started = true }, { finished = true })
        }
        assertTrue(started)
        assertTrue(finished)
    }

    @Test fun replayedFrameRejected() {
        val expires = expiry
        val frame = encoded("status", expires)
        val receiver = receiver(frame + frame, expires)
        assertEquals("status", receiver.receive())
        assertThrows(IllegalStateException::class.java) { receiver.receive() }
    }

    @Test fun modifiedPayloadRejected() {
        val expires = expiry
        val bytes = encoded("status", expires)
        bytes[bytes.lastIndex] = 'X'.code.toByte()
        assertThrows(IllegalStateException::class.java) { receiver(bytes, expires).receive() }
    }

    @Test fun otherDirectionSessionUidOrExpiryCannotAuthenticateFrame() {
        val expires = expiry
        val bytes = encoded("status", expires)
        listOf(
            channel(bytes, expires, true),
            channel(bytes, expires, false, nonce = "03".repeat(32)),
            channel(bytes, expires, false, uid = 10124),
            channel(bytes, expires + 1, false),
        ).forEach { receiver -> assertThrows(IllegalStateException::class.java) { receiver.receive() } }
    }

    @Test fun framePayloadLimitAppliesBeforeAcceptance() {
        val expires = expiry
        assertThrows(IllegalArgumentException::class.java) { receiver(encoded("x".repeat(500), expires), expires).receive(10) }
    }

    private fun encoded(payload: String, expires: Long): ByteArray {
        val output = ByteArrayOutputStream()
        BackgroundPhoneProtocol.Channel(DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(output),
            key, clientNonce, serverNonce, 10123, expires, true).send(payload)
        return output.toByteArray()
    }
    private fun receiver(bytes: ByteArray, expires: Long) = channel(bytes, expires, false)
    private fun channel(bytes: ByteArray, expires: Long, clientSide: Boolean, nonce: String = serverNonce, uid: Int = 10123) =
        BackgroundPhoneProtocol.Channel(DataInputStream(ByteArrayInputStream(bytes)), DataOutputStream(ByteArrayOutputStream()),
            key, clientNonce, nonce, uid, expires, clientSide)
}
