package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardPasteRestorerTest {
    @Test
    fun restoreReturnsTheDisplacedUserClip() {
        val clipboard = FakeClipboard(FakeClip("user secret"))
        val restorer = ClipboardPasteRestorer<FakeClip>()

        val token = restorer.writeForPaste(clipboard, "pasted query")
        assertEquals(FakeClip("pasted query"), clipboard.current)

        restorer.restore(clipboard, token)

        assertEquals(FakeClip("user secret"), clipboard.current)
        assertNull(restorer.pendingToken())
    }

    @Test
    fun backToBackPastesStillRestoreTheOriginalUserClip() {
        val clipboard = FakeClipboard(FakeClip("user secret"))
        val restorer = ClipboardPasteRestorer<FakeClip>()

        val first = restorer.writeForPaste(clipboard, "first pasted text")
        // The first delayed restore was refused (e.g. the task was interrupted) before the next paste.
        val second = restorer.writeForPaste(clipboard, "second pasted text")
        restorer.restore(clipboard, first)
        assertEquals("stale restore must not consume the newer paste", second, restorer.pendingToken())
        restorer.restore(clipboard, second)

        assertEquals(FakeClip("user secret"), clipboard.current)
    }

    @Test
    fun interruptDrainAfterRefusedRestoreDoesNotLeavePastedText() {
        val clipboard = FakeClipboard(FakeClip("user secret"))
        val restorer = ClipboardPasteRestorer<FakeClip>()
        restorer.writeForPaste(clipboard, "private address")

        // onInterrupt: the lease-guarded delayed restore is refused, so the drain must run.
        restorer.drain(clipboard, restorer.pendingToken())

        assertEquals(FakeClip("user secret"), clipboard.current)
        assertNull(restorer.pendingToken())
    }

    @Test
    fun drainForOlderTokenLeavesNewerPasteAlone() {
        val clipboard = FakeClipboard(FakeClip("user secret"))
        val restorer = ClipboardPasteRestorer<FakeClip>()
        val interrupted = restorer.writeForPaste(clipboard, "first")
        val newer = restorer.writeForPaste(clipboard, "second")

        restorer.drain(clipboard, interrupted)

        assertEquals(FakeClip("second"), clipboard.current)
        assertEquals(newer, restorer.pendingToken())
        restorer.drain(clipboard)
        assertEquals(FakeClip("user secret"), clipboard.current)
    }

    @Test
    fun clipCopiedByUserAfterPasteIsNeverOverwritten() {
        val clipboard = FakeClipboard(FakeClip("user secret"))
        val restorer = ClipboardPasteRestorer<FakeClip>()
        val token = restorer.writeForPaste(clipboard, "pasted")
        clipboard.current = FakeClip("user copied later")

        restorer.restore(clipboard, token)

        assertEquals(FakeClip("user copied later"), clipboard.current)
    }

    @Test
    fun userCopyBetweenPastesBecomesTheNewOriginal() {
        val clipboard = FakeClipboard(FakeClip("old"))
        val restorer = ClipboardPasteRestorer<FakeClip>()
        restorer.writeForPaste(clipboard, "first")
        clipboard.current = FakeClip("user copied later")
        val second = restorer.writeForPaste(clipboard, "second")

        restorer.restore(clipboard, second)

        assertEquals(FakeClip("user copied later"), clipboard.current)
    }

    @Test
    fun emptyClipboardIsClearedAfterPaste() {
        val clipboard = FakeClipboard(null)
        val restorer = ClipboardPasteRestorer<FakeClip>()
        val token = restorer.writeForPaste(clipboard, "pasted")

        restorer.restore(clipboard, token)

        assertNull(clipboard.current)
        assertEquals(1, clipboard.clearCount)
    }

    @Test
    fun failedWriteRecordsNothingAndKeepsEarlierPendingRestore() {
        val clipboard = FakeClipboard(FakeClip("user secret"))
        val restorer = ClipboardPasteRestorer<FakeClip>()
        val first = restorer.writeForPaste(clipboard, "first")

        runCatching {
            restorer.writeForPaste(clipboard, "second") { throw IllegalStateException("cancelled") }
        }

        assertEquals(first, restorer.pendingToken())
        restorer.restore(clipboard)
        assertEquals(FakeClip("user secret"), clipboard.current)
    }

    @Test
    fun drainWithoutClipboardDropsPendingRestore() {
        val clipboard = FakeClipboard(FakeClip("user secret"))
        val restorer = ClipboardPasteRestorer<FakeClip>()
        restorer.writeForPaste(clipboard, "pasted")

        restorer.drain(null)

        assertNull(restorer.pendingToken())
    }

    private data class FakeClip(val text: String)

    private class FakeClipboard(var current: FakeClip?) : PasteClipboard<FakeClip> {
        var clearCount = 0

        override fun primaryClip(): FakeClip? = current

        override fun holdsText(text: String): Boolean = current?.text == text

        override fun setText(text: String) {
            current = FakeClip(text)
        }

        override fun setClip(clip: FakeClip) {
            current = clip
        }

        override fun clear() {
            clearCount += 1
            current = null
        }
    }
}
