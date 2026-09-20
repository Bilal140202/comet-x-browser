@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cometx.browser.ui.cloud

import androidx.compose.foundation.layout.Arrangement
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
import com.cometx.browser.ai.KeyFormat
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.social.DiscordApi
import com.cometx.browser.social.DiscordNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Discord card (v2.2.0) — Compose/Material 3 section of the Cloud AI center.
 * The user pastes their OWN bot token + a channel id; when enabled, finished
 * background-agent tasks are pushed as one embed per task. All REST paths here
 * were verified against the live Discord API (see class doc of DiscordApi).
 */
@Composable
fun DiscordCard(settings: SettingsRepository, onOpenGuide: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val enabled = remember { mutableStateOf(settings.discordEnabled()) }
    val channelId = remember { mutableStateOf(settings.discordChannelId()) }
    val tokenInput = remember { mutableStateOf("") }
    val reveal = remember { mutableStateOf(false) }
    val busy = remember { mutableStateOf(false) }
    val status = remember { mutableStateOf("") }
    val statusOk = remember { mutableStateOf<Boolean?>(null) }
    val savedTokenMask = remember {
        mutableStateOf(settings.discordToken()?.let { KeyFormat.mask(it) } ?: "")
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
                        "Post a result embed to one of your channels when a background task finishes. " +
                            "Uses your own bot (REST only — one small message per task).",
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
            if (savedTokenMask.value.isNotBlank()) {
                Text(
                    "Bot token ${savedTokenMask.value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurface
                )
            } else {
                Text(
                    "No bot token yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = tokenInput.value,
                onValueChange = { tokenInput.value = it },
                label = { Text("Bot token (Discord Developer Portal → Bot)") },
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
                label = { Text("Channel ID (right-click a channel → Copy ID)") },
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
                                val (ok, msg) = DiscordApi.validateToken(token)
                                if (ok) {
                                    settings.setDiscordToken(token)
                                    savedTokenMask.value = KeyFormat.mask(token)
                                    tokenInput.value = ""
                                }
                                ok to msg
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
                            val res = withContext(Dispatchers.IO) { DiscordNotifier(settings).sendTest() }
                            statusOk.value = DiscordNotifier(settings).configured() && !res.startsWith("Save")
                            status.value = res
                            busy.value = false
                        }
                    },
                    enabled = !busy.value
                ) {
                    Icon(Icons.Filled.Send, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Send test embed")
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
                "The bot must be a member of the channel's server. Comet-X never reads messages — " +
                    "it only posts results. Push happens for COMPLETED and FAILED background tasks.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}
