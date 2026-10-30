package com.bytedance.zgx.solin.device

import android.content.ClipData
import android.content.ClipboardManager

internal const val SOLIN_PASTE_CLIP_LABEL = "Solin输入"

/**
 * The few clipboard operations the paste fallback needs, abstracted so the restore bookkeeping in
 * [ClipboardPasteRestorer] is JVM-testable. [C] is the platform clip type ([ClipData] on device).
 */
internal interface PasteClipboard<C : Any> {
    /** The current primary clip, or null when empty. May throw when the clipboard is unreadable. */
    fun primaryClip(): C?

    /**
     * True when the primary clip is still exactly [text] (single item, compared by value). Any read
     * failure must report false so an unreadable clipboard is never overwritten.
     */
    fun holdsText(text: String): Boolean

    fun setText(text: String)

    fun setClip(clip: C)

    fun clear()
}

/**
 * Tracks the clipboard content Solin owes back after a clipboard-paste fallback.
 *
 * Pasting user text through the clipboard leaves that text readable by every other app, so each
 * paste records the clip it displaced and restores it later — content-checked, so a clip the user
 * copied in the meantime is never destroyed. Two invariants matter:
 *
 * - **The original user clip survives back-to-back pastes.** If a restore is still pending when the
 *   next paste starts and the clipboard still holds Solin's previous text, the pending original is
 *   carried forward instead of capturing Solin's own text as "previous" (which would make the final
 *   restore put pasted user text back on the clipboard).
 * - **Restores are token-scoped.** A delayed restore for an older paste never consumes a newer
 *   paste's pending entry, so a stale callback cannot restore mid-paste.
 *
 * Thread-safety: bookkeeping is guarded by a private lock; clipboard IPC runs outside it.
 */
internal class ClipboardPasteRestorer<C : Any> {
    private data class Pending<C : Any>(
        val token: Long,
        val previousClip: C?,
        val writtenText: String,
    )

    private val lock = Any()
    private var pending: Pending<C>? = null
    private var nextToken = 0L

    /**
     * Captures the clip to restore, runs [write] (which must put [text] on the clipboard), and
     * records the pending restore. Returns the restore token. When [write] throws nothing is
     * recorded and any existing pending restore is kept.
     */
    fun writeForPaste(
        clipboard: PasteClipboard<C>,
        text: String,
        write: () -> Unit = { clipboard.setText(text) },
    ): Long {
        val existing = synchronized(lock) { pending }
        // Still holding Solin's previous paste: the clip to give back is that paste's original, not
        // Solin's own text. Otherwise (nothing pending, or the user copied something since) the
        // current clip is the original.
        val previousClip = if (existing != null && clipboard.holdsText(existing.writtenText)) {
            existing.previousClip
        } else {
            runCatching { clipboard.primaryClip() }.getOrNull()
        }
        write()
        return synchronized(lock) {
            nextToken += 1
            pending = Pending(token = nextToken, previousClip = previousClip, writtenText = text)
            nextToken
        }
    }

    /** Token of the restore currently owed, or null when nothing is pending. */
    fun pendingToken(): Long? = synchronized(lock) { pending?.token }

    /**
     * Restores the displaced clip if the clipboard still holds what Solin wrote. With a [token],
     * only that paste's restore runs; a newer pending paste is left alone.
     */
    fun restore(clipboard: PasteClipboard<C>, token: Long? = null) {
        val target = synchronized(lock) {
            val current = pending ?: return
            if (token != null && current.token != token) return
            pending = null
            current
        }
        runCatching {
            if (!clipboard.holdsText(target.writtenText)) return
            val previousClip = target.previousClip
            if (previousClip != null) {
                clipboard.setClip(previousClip)
            } else {
                clipboard.clear()
            }
        }
    }

    /**
     * Teardown/interrupt drain: restores (token-scoped) when a clipboard is available, otherwise
     * drops the pending entry since nothing can be done without one.
     */
    fun drain(clipboard: PasteClipboard<C>?, token: Long? = null) {
        if (clipboard == null) {
            synchronized(lock) {
                if (token == null || pending?.token == token) pending = null
            }
            return
        }
        restore(clipboard, token)
    }
}

/** [PasteClipboard] over the system [ClipboardManager]. */
internal class AndroidPasteClipboard(
    private val clipboard: ClipboardManager,
) : PasteClipboard<ClipData> {
    override fun primaryClip(): ClipData? = clipboard.primaryClip

    /**
     * Compared by value rather than by clip identity because the system hands back a fresh
     * [ClipData] instance on every read.
     */
    override fun holdsText(text: String): Boolean =
        runCatching {
            val clip = clipboard.primaryClip ?: return false
            if (clip.itemCount != 1) return false
            clip.getItemAt(0)?.text?.toString() == text
        }.getOrDefault(false)

    override fun setText(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(SOLIN_PASTE_CLIP_LABEL, text))
    }

    override fun setClip(clip: ClipData) {
        clipboard.setPrimaryClip(clip)
    }

    override fun clear() {
        // minSdk is 28 (P), where clearPrimaryClip() is always available.
        clipboard.clearPrimaryClip()
    }
}
