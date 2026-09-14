package com.cometx.browser.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cometx.browser.ui.MainActivity

/**
 * AgentNotifications (v1.8.0) — the notification-bar monitoring surface for
 * background agent tasks (the user's contract: "monitor the updates all
 * through notification bar").
 *
 * One silent IMPORTANCE_LOW channel, one ongoing notification whose content
 * mirrors the task record:
 *   RUNNING            → "Step 3/8 · clicked Search" + progress bar
 *   WAITING_NETWORK    → "Waiting for network — resumes automatically"
 *   AWAITING_CONFIRM   → Approve / Deny buttons (high-risk action gate)
 *   AWAITING_USER      → direct Reply (RemoteInput) — answer from the shade
 *   AWAITING_CHALLENGE → "tap to take control" (opens the app)
 *   terminal           → completion/failure summary, swipeable
 *
 * Every PendingIntent is FLAG_IMMUTABLE; every builder call is wrapped by the
 * service in runCatching so a notification failure can never kill a task.
 */
object AgentNotifications {

    const val CHANNEL_ID = "cometx_agent"
    const val NOTIFICATION_ID = 47002
    const val EXTRA_OPEN_PANEL = "open_agent_panel"
    const val KEY_REPLY_TEXT = "cometx.agent.REPLY_TEXT"

    // -------------------------------------------------------------- channel

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent tasks", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background agent task progress"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    // --------------------------------------------------------------- build

    fun build(context: Context, rec: BackgroundAgentStore.TaskRecord): Notification {
        ensureChannel(context)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        b.setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Comet-X Agent: ${rec.goal.take(60)}")
            .setContentText(statusLine(rec))
            .setOnlyAlertOnce(true)
            .setOngoing(rec.isActive)
            .setAutoCancel(!rec.isActive)
            .setContentIntent(contentIntent(context))

        when (rec.state) {
            BackgroundAgentStore.STATE_RUNNING,
            BackgroundAgentStore.STATE_WAITING_NETWORK,
            BackgroundAgentStore.STATE_AWAITING_CONFIRM,
            BackgroundAgentStore.STATE_AWAITING_USER,
            BackgroundAgentStore.STATE_AWAITING_CHALLENGE -> {
                if (rec.stepBudget > 0) {
                    b.setProgress(rec.stepBudget, rec.stepUsed.coerceAtMost(rec.stepBudget), false)
                } else {
                    b.setProgress(0, 0, true)
                }
            }
        }

        when (rec.state) {
            BackgroundAgentStore.STATE_AWAITING_CONFIRM -> {
                b.addAction(0, "Approve", controlIntent(context, AgentTaskService.ACTION_CONFIRM, true))
                b.addAction(0, "Deny", controlIntent(context, AgentTaskService.ACTION_CONFIRM, false))
            }
            BackgroundAgentStore.STATE_AWAITING_USER -> b.addAction(0, "Reply", replyIntent(context))
            BackgroundAgentStore.STATE_RUNNING,
            BackgroundAgentStore.STATE_WAITING_NETWORK,
            BackgroundAgentStore.STATE_AWAITING_CHALLENGE ->
                b.addAction(0, "Stop", controlIntent(context, AgentTaskService.ACTION_STOP, false))
        }
        return b.build()
    }

    /** Pure state→text mapping (unit-tested): the shade's one-line status. */
    fun statusLine(rec: BackgroundAgentStore.TaskRecord): String = when (rec.state) {
        BackgroundAgentStore.STATE_RUNNING ->
            (if (rec.stepBudget > 0) "Step ${rec.stepUsed}/${rec.stepBudget} · " else "") + rec.detail.take(110)
        BackgroundAgentStore.STATE_WAITING_NETWORK ->
            "Waiting for network — the task resumes automatically"
        BackgroundAgentStore.STATE_AWAITING_CONFIRM ->
            "Needs your approval: ${rec.detail.take(96)}"
        BackgroundAgentStore.STATE_AWAITING_USER ->
            "Agent asks: ${rec.detail.take(100)}"
        BackgroundAgentStore.STATE_AWAITING_CHALLENGE ->
            "Human verification needed — tap to take control"
        BackgroundAgentStore.STATE_COMPLETED ->
            "✓ Task complete — ${rec.detail.take(96)}"
        BackgroundAgentStore.STATE_FAILED ->
            "✗ Task failed — ${rec.detail.take(96)}"
        BackgroundAgentStore.STATE_CANCELLED ->
            "■ Task stopped"
        BackgroundAgentStore.STATE_INTERRUPTED ->
            "Task interrupted (device restart?) — reopen Comet-X to re-run"
        else -> rec.detail.take(110)
    }

    // -------------------------------------------------------- pending intents

    private fun contentIntent(context: Context): PendingIntent {
        val it = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_PANEL, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, 1, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun controlIntent(context: Context, action: String, approve: Boolean): PendingIntent {
        val it = Intent(context, AgentTaskService::class.java).apply {
            this.action = action
            putExtra(AgentTaskService.EXTRA_APPROVE, approve)
        }
        return PendingIntent.getService(
            context, if (approve) 2 else 3, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun replyIntent(context: Context): PendingIntent {
        val it = Intent(context, AgentTaskService::class.java).apply {
            action = AgentTaskService.ACTION_REPLY
        }
        return PendingIntent.getService(
            context, 4, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** RemoteInput label shown over the reply field in the shade. */
    fun remoteInput(): RemoteInput =
        RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Answer the agent").build()
}
