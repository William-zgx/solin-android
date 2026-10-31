package com.bytedance.zgx.solin.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.solin.data.SolinDatabase
import com.bytedance.zgx.solin.orchestration.RoomAgentTraceStore

/**
 * `adb shell am broadcast -n com.bytedance.zgx.solin/.debug.AgentRunReportReceiver` returns the
 * latest agent run as JSON in the broadcast result data (see [agentRunReportJson]). Read-only: a
 * fresh trace store only reads persisted steps, and the answer is read from the run's chat session.
 */
class AgentRunReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            val report = runCatching {
                val database = SolinDatabase.get(context.applicationContext)
                val store = RoomAgentTraceStore(database.agentTraceDao())
                val run = store.recentRunSummaries(limit = 1, stepLimit = 1).firstOrNull()?.run
                val answer = run?.sessionId?.let { sessionId ->
                    transcriptAnswer(database.sessionDao().messagesForSession(sessionId))
                }
                agentRunReportJson(run, run?.let { store.steps(it.id) }.orEmpty(), answer).toString()
            }
            pending.resultCode = if (report.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED
            pending.resultData = report.getOrElse { error -> """{"error":"${error.javaClass.simpleName}"}""" }
            pending.finish()
        }.start()
    }
}
