package com.cometx.browser.background

import com.cometx.browser.ai.HttpTransport
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.util.Http
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * DiscordNotifier (v2.2.0) — ADDITIVE mirror of background-agent task events
 * into a Discord channel through a user-configured bot ("Social Automation
 * Bot" shape: Application ID + Bot token, Send-Messages permission).
 *
 * Why: v1.8.0 monitors background tasks through the Android notification
 * bar — which only exists on the device. A Discord channel gives the user a
 * live task feed on any other device (desktop, second phone).
 *
 * Contract (v2.2.0 lock DM-1..DM-6):
 *  - disabled or unconfigured → NO network call, byte-identical v2.1.0 paths
 *  - bot token is stored ONLY through SecureStore (Keystore-encrypted),
 *    channel id in plain prefs (not a secret)
 *  - fire-and-forget: a Discord outage can never affect a task, a
 *    notification, or the service (all failures are logged and dropped)
 *  - messages are plain markdown content (no embeds) with an 1800-char cap
 *    (Discord's limit is 2000 — headroom for the title line)
 *  - throttled to ≥2.5s between posts; forced events (start/gates/final)
 *    bypass the throttle but never the config check
 */
object DiscordNotifier {

    const val API_BASE = "https://discord.com/api/v10"
    const val CONTENT_LIMIT = 1800

    /** Milestone cadence for STEP events (progress spam would hit rate limits). */
    const val STEP_MILESTONE_EVERY = 5

    enum class Event(val title: String) {
        STARTED("Task started"),
        STEP("Progress"),
        GATE("Waiting for you"),
        FINISHED_OK("Task completed"),
        FINISHED_FAIL("Task failed"),
        FINISHED_CANCELLED("Task stopped"),
        TEST("Test message")
    }

    data class Config(val enabled: Boolean, val botToken: String?, val channelId: String?) {
        val ready: Boolean
            get() = enabled && !botToken.isNullOrBlank() && !channelId.isNullOrBlank()
    }

    fun configFrom(settings: SettingsRepository): Config =
        Config(settings.discordEnabled(), settings.discordBotToken(), settings.discordChannelId())

    /** Pure formatting — unit tested. Never embeds key material. */
    fun buildBody(
        event: Event,
        goal: String,
        detail: String,
        stepUsed: Int = 0,
        stepBudget: Int = 0
    ): JSONObject {
        val sb = StringBuilder("**Comet-X agent — ${event.title}**")
        when (event) {
            Event.STARTED -> sb.append("\nGoal: ").append(goal.take(300))
            Event.STEP -> if (stepBudget > 0) {
                sb.append("\nStep ").append(stepUsed).append('/').append(stepBudget)
            }
            Event.FINISHED_OK, Event.FINISHED_FAIL, Event.FINISHED_CANCELLED ->
                if (stepBudget > 0) {
                    sb.append(" · ").append(stepUsed).append('/').append(stepBudget).append(" steps")
                }
            Event.GATE, Event.TEST -> {}
        }
        val d = detail.trim()
        if (d.isNotEmpty()) {
            val room = CONTENT_LIMIT - sb.length - 1
            if (room > 0) sb.append('\n').append(d.take(room))
        }
        return JSONObject().put("content", sb.toString().take(CONTENT_LIMIT))
    }

    /** POST one message. Returns the raw response (null when not configured). */
    suspend fun post(
        transport: HttpTransport,
        config: Config,
        body: JSONObject
    ): Http.Response? {
        if (!config.ready) return null
        return withContext(Dispatchers.IO) {
            transport.postJson(
                "$API_BASE/channels/${config.channelId}/messages",
                body.toString(),
                mapOf("Authorization" to "Bot ${config.botToken}"),
                15_000
            )
        }
    }

    @Volatile private var lastPostAt = 0L

    /**
     * Fire-and-forget mirror used by [AgentTaskService]. Reads the config
     * synchronously (cheap prefs/SecureStore reads), then posts off-scope.
     * Never throws; failures are logged and dropped.
     */
    fun mirrorAsync(
        scope: CoroutineScope,
        settings: SettingsRepository,
        rec: BackgroundAgentStore.TaskRecord,
        event: Event,
        detail: String,
        force: Boolean = false
    ) {
        runCatching {
            val config = configFrom(settings)
            if (!config.ready) return
            val now = System.currentTimeMillis()
            if (!force && now - lastPostAt < 2_500) return
            lastPostAt = now
            val body = buildBody(event, rec.goal, detail, rec.stepUsed, rec.stepBudget)
            scope.launch(Dispatchers.IO) {
                runCatching { post(HttpTransport.REAL, config, body) }
                    .onFailure {
                        Logx.w("discord: post failed: ${it.message?.take(160)}")
                    }
                    .onSuccess { resp ->
                        if (resp != null && !resp.ok) {
                            Logx.w("discord: post HTTP ${resp.code}: ${resp.body.take(160)}")
                        }
                    }
            }
        }.onFailure { Logx.w("discord: mirror failed: ${it.message?.take(160)}") }
    }
}
