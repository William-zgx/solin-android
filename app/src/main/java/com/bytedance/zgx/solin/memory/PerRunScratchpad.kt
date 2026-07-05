package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.orchestration.AgentSurvivalRules

/**
 * Per-run scratchpad for the agent to record notes during a single agent run.
 *
 * Inspired by Open-AutoGLM's `Note` action which lets the agent record page content
 * for later summarization. This is distinct from long-term semantic memory:
 * - Scratchpad is per-run (cleared when run ends)
 * - Long-term memory persists across runs
 *
 * The scratchpad is included in subsequent model calls within the same run so the
 * agent can reference its earlier notes.
 */
class PerRunScratchpad {

    data class ScratchpadEntry(
        val index: Int,
        val content: String,
        val recordedAtMillis: Long,
    )

    private val notesByRunId = mutableMapOf<String, MutableList<ScratchpadEntry>>()

    /**
     * Add a note to the scratchpad for the given run.
     * Returns the 1-based index of the new note.
     */
    @Synchronized
    fun addNote(runId: String, content: String): Int {
        val notes = notesByRunId.getOrPut(runId) { mutableListOf() }
        val index = notes.size + 1
        notes.add(
            ScratchpadEntry(
                index = index,
                content = content,
                recordedAtMillis = System.currentTimeMillis(),
            )
        )
        enforceCapacity(runId, notes)
        return index
    }

    /** Get all notes for a run, in order. */
    @Synchronized
    fun getNotes(runId: String): List<ScratchpadEntry> =
        notesByRunId[runId]?.toList().orEmpty()

    /** Clear all notes for a run. Called when the run ends. */
    @Synchronized
    fun clear(runId: String) {
        notesByRunId.remove(runId)
    }

    /** Returns true if the run has any notes. */
    @Synchronized
    fun hasNotes(runId: String): Boolean =
        notesByRunId[runId]?.isNotEmpty() == true

    /**
     * Format notes for inclusion in the model prompt.
     * Returns null if there are no notes. Truncates to [maxChars] if needed.
     */
    @Synchronized
    fun formatForPrompt(runId: String, maxChars: Int = AgentSurvivalRules.MAX_SCRATCHPAD_TOTAL_CHARS): String? {
        val notes = notesByRunId[runId] ?: return null
        if (notes.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("## 运行记录（本次操作过程中记录的笔记）\n\n")
        sb.append("以下是你在本次运行中记录的笔记，供后续步骤参考：\n")

        var totalChars = sb.length
        for (entry in notes) {
            val line = "${entry.index}. ${entry.content}\n"
            if (totalChars + line.length > maxChars) {
                val remaining = maxChars - totalChars
                if (remaining > 20) {
                    sb.append(line.take(remaining))
                    sb.append("…[笔记因长度限制截断]\n")
                }
                break
            }
            sb.append(line)
            totalChars += line.length
        }
        return sb.toString()
    }

    private fun enforceCapacity(runId: String, notes: MutableList<ScratchpadEntry>) {
        // Drop oldest notes if count exceeds max
        while (notes.size > AgentSurvivalRules.MAX_NOTES_PER_RUN) {
            notes.removeAt(0)
        }
        // Re-index after dropping
        if (notes.size > 1 && notes.first().index != 1) {
            val reindexed = notes.mapIndexed { i, entry ->
                entry.copy(index = i + 1)
            }
            notes.clear()
            notes.addAll(reindexed)
        }
    }
}
