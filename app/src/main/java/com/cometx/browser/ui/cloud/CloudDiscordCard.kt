@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cometx.browser.ui.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cometx.browser.ai.HttpTransport
import com.cometx.browser.ai.KeyFormat
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.background.DiscordNotifier
import com.cometx.browser.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Discord card (v2.3.0) — the Compose/Material 3 face of the v2.2.0 Discord
 * agent monitor (backend: `background/DiscordNotifier`, contracts DM-1..DM-6).
 * The user pastes their OWN bot token + a channel id; background-agent task
 * events then mirror into that channel. All REST paths were verified against
 * the live Discord API before shipping (see docs/ai/CLOUD_AI.md).
 *
 * This card only ADDS a second UI on the same settings keys — the legacy XML
 * section stays authoritative and both stay consistent by construction.
 */
@Composable
fun DiscordCard(settings: SettingsRepository, onOpenGuide: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val enabled = remember { mutableStateOf(settings.discordEnabled()) }
    val channelId = remember { mutableStateOf(settings.discordChannelId() ?: "") }
    val tokenInput = remember { mutableStateOf("") }
    val reveal = remember { mutableStateOf(false) }
    val busy = remember { mutableStateOf(false) }
    val status = remember { mutableStateOf("") }
    val statusOk = remember { mutableStateOf<Boolean?>(null) }
    val savedTokenMask = remember {
        mutableStateOf(settings.discordBotToken()?.let { KeyFormat.mask(it) } ?: "")
    }

    fun statusFor(code: Int): String = when (code) {
        200, 204 -> "Delivered — agent events will appear in that channel"
        401 -> "401 — token rejected by Discord (re-paste it)"
        403 -> "403 — the bot lacks permission in that channel"
        404 -> "404 — unknown channel id (Developer Mode → Copy Channel ID)"
        429 -> "429 — rate limited; try again in a moment"
        else -> "HTTP $code — see docs/ai/CLOUD_AI.md"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = cs.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Discord — agent results push",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Watch background tasks from any device: start, step milestones, approval gates and the result land in your channel. The bot never reads messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled.value,
                    onCheckedChange = {
                        settings.setDiscordEnabled(it)
                        enabled.value = it
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (savedTokenMask.value.isNotBlank()) "Bot token ${savedTokenMask.value}"
                else "No bot token yet",
                style = MaterialTheme.typography.bodySmall,
                color = if (savedTokenMask.value.isNotBlank()) cs.onSurface else cs.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = tokenInput.value,
                onValueChange = { tokenInput.value = it },
                label = { Text("Bot token (discord.com/developers → Bot)") },
                singleLine = true,
                visualTransformation = if (reveal.value) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { reveal.value = !reveal.value }) {
                        Text(if (reveal.value) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = channelId.value,
                onValueChange = {
                    channelId.value = it
                    settings.setDiscordChannelId(it)
                },
                label = { Text("Channel ID (right-click channel → Copy ID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        busy.value = true
                        status.value = "Validating…"
                        scope.launch {
                            val token = tokenInput.value.trim()
                            val res = withContext(Dispatchers.IO) {
                                if (token.isEmpty()) return@withContext false to "Type a token first"
                                val resp = Http.get(
                                    "${DiscordNotifier.API_BASE}/users/@me",
                                    mapOf("Authorization" to "Bot $token"), 15_000
                                )
                                if (resp.ok) {
                                    settings.setDiscordBotToken(token)
                                    savedTokenMask.value = KeyFormat.mask(token)
                                    tokenInput.value = ""
                                    val name = runCatching {
                                        JSONObject(resp.body).optString("username")
                                    }.getOrNull()
                                    true to (name?.takeIf { it.isNotBlank() } ?: "Token saved")
                                } else {
                                    false to statusFor(resp.code)
                                }
                            }
                            statusOk.value = res.first
                            status.value = res.second
                            busy.value = false
                        }
                    },
                    enabled = !busy.value
                ) { Text("Validate & save token") }
                Spacer(Modifier.width(8.dp))
                if (busy.value) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        busy.value = true
                        status.value = "Sending…"
                        scope.launch {
                            val config = DiscordNotifier.configFrom(settings)
                            val resp = DiscordNotifier.post(
                                HttpTransport.REAL, config,
                                DiscordNotifier.buildBody(
                                    DiscordNotifier.Event.TEST,
                                    goal = "", detail = "If you can read this, agent events will land here."
                                )
                            )
                            statusOk.value = resp?.ok == true
                            status.value = resp?.let { statusFor(it.code) }
                                ?: "Enable the toggle and save token + channel first"
                            busy.value = false
                        }
                    },
                    enabled = !busy.value
                ) {
                    Icon(Icons.Filled.Send, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Send test message")
                }
            }

            if (status.value.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when (statusOk.value) {
                        true -> cs.primaryContainer
                        false -> cs.errorContainer
                        null -> cs.surfaceVariant
                    }
                ) {
                    Text(
                        status.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (statusOk.value) {
                            true -> cs.onPrimaryContainer
                            false -> cs.onErrorContainer
                            null -> cs.onSurfaceVariant
                        },
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "The bot must be a member of the channel's server (Send Messages). " +
                    "Events are posted to discord.com only; the token is Keystore-encrypted and never logged.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}
