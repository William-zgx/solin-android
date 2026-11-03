package com.bytedance.zgx.solin.device.background

import java.net.Socket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** No socket connection, Android service, wall-clock wait or protocol traffic is used. */
class BackgroundPhoneRunBindingTest {
    private val runIds = mutableListOf<String>()

    @After fun clearBindings() {
        runIds.forEach(BackgroundPhoneSession::forgetRun)
        BackgroundPhoneSession.selectForAgent(null)
    }

    @Test fun expiredSelectionRemainsBoundInsteadOfFallingBackToMain() {
        val session = newSession()
        BackgroundPhoneSession.selectForAgent(session)
        val run = run()
        BackgroundPhoneSession.captureForRun(run)
        setField(session, "expiresAtMs", 0L)
        assertSame(session, BackgroundPhoneSession.selectedForAgent())
        assertSame(session, BackgroundPhoneSession.forRun(run))
        val following = run()
        BackgroundPhoneSession.captureForRun(following)
        assertSame(session, BackgroundPhoneSession.forRun(following))
        assertThrows(IllegalStateException::class.java) {
            binding(session).requireLocallyActive()
        }
    }

    @Test fun closeRetainsCapturedSessionAndSelectionTombstone() {
        val session = newSession()
        BackgroundPhoneSession.selectForAgent(session)
        val run = run()
        BackgroundPhoneSession.captureForRun(run)
        // Avoid transport entirely; close's already-closed path must preserve both tombstones.
        setField(session, "closed", true)
        session.close()
        assertSame(session, BackgroundPhoneSession.selectedForAgent())
        assertSame(session, BackgroundPhoneSession.forRun(run))
        assertThrows(IllegalStateException::class.java) { binding(session).requireLocallyActive() }
    }

    @Test fun choosingAnotherSessionCannotRetargetAnExistingRun() {
        val first = newSession()
        val second = newSession()
        BackgroundPhoneSession.selectForAgent(first)
        val oldRun = run()
        BackgroundPhoneSession.captureForRun(oldRun)
        BackgroundPhoneSession.selectForAgent(second)
        BackgroundPhoneSession.captureForRun(oldRun) // repeated capture is deliberately idempotent
        val newRun = run()
        BackgroundPhoneSession.captureForRun(newRun)
        assertSame(first, BackgroundPhoneSession.forRun(oldRun))
        assertSame(second, BackgroundPhoneSession.forRun(newRun))
    }

    @Test fun explicitMainSelectionOnlyAffectsNewRuns() {
        val session = newSession()
        BackgroundPhoneSession.selectForAgent(session)
        val oldRun = run()
        BackgroundPhoneSession.captureForRun(oldRun)
        BackgroundPhoneSession.selectForAgent(null)
        val newRun = run()
        BackgroundPhoneSession.captureForRun(newRun)
        assertSame(session, BackgroundPhoneSession.forRun(oldRun))
        assertNull(BackgroundPhoneSession.forRun(newRun))
    }

    @Test fun foregroundRunRemainsForegroundAfterBackgroundSelection() {
        BackgroundPhoneSession.selectForAgent(null)
        val run = run()
        BackgroundPhoneSession.captureForRun(run)
        BackgroundPhoneSession.selectForAgent(newSession())
        BackgroundPhoneSession.captureForRun(run)
        assertNull(BackgroundPhoneSession.forRun(run))
    }

    @Test fun unknownForegroundRunIsCompatibleAndNeverReadsCurrentSelection() {
        BackgroundPhoneSession.selectForAgent(newSession())
        assertNull(BackgroundPhoneSession.forRun(run()))
    }

    @Test fun persistedBackgroundMarkerWithoutLiveObjectFailsClosed() {
        val run = run()
        BackgroundPhoneSession.selectForAgent(newSession())
        BackgroundPhoneSession.captureForRun(run)
        // Simulate process loss of live sessions; the persisted run marker remains.
        BackgroundPhoneSession.releaseRun(run)
        assertThrows(IllegalStateException::class.java) { BackgroundPhoneSession.forRun(run) }
        BackgroundPhoneSession.selectForAgent(null)
        assertThrows(IllegalStateException::class.java) { BackgroundPhoneSession.captureForRun(run) }
    }

    @Test fun forgottenTerminalRunDoesNotReuseCurrentSelection() {
        BackgroundPhoneSession.selectForAgent(newSession())
        val run = run()
        BackgroundPhoneSession.captureForRun(run)
        BackgroundPhoneSession.forgetRun(run)
        assertNull(BackgroundPhoneSession.forRun(run))
    }

    private fun run(): String = "binding-test-${UUID.randomUUID()}".also(runIds::add)

    private fun binding(session: BackgroundPhoneSession) = BackgroundPhoneSession.Binding(
        session, BackgroundPhoneState(8, "com.example.app", 720, 1280, 320, true),
    )

    private fun setField(session: BackgroundPhoneSession, name: String, value: Any) {
        BackgroundPhoneSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }

    private fun newSession(): BackgroundPhoneSession {
        val socket = Socket() // Inert, unconnected JVM socket; no Android constructor or transport.
        return BackgroundPhoneSession::class.java.getDeclaredConstructor(
            Socket::class.java, DataInputStream::class.java, DataOutputStream::class.java,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(
            socket, DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            System.currentTimeMillis() + 60_000L,
        )
    }
}
