@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cometx.browser.ui.cloud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cometx.browser.ai.CustomOpenAIProvider
import com.cometx.browser.ai.GroqProvider
import com.cometx.browser.ai.HuggingFaceProvider
import com.cometx.browser.ai.KeyFormat
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.NvidiaProvider
import com.cometx.browser.ai.OpenAICompatibleProvider
import com.cometx.browser.ai.OpenRouterProvider
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.ai.UrlNormalizer
import com.cometx.browser.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cloud AI center (v2.2.0) — the app's first Jetpack Compose / Material 3
 * Expressive surface. One place to paste PERSONAL API keys, test the live
 * connection and order the fallback chain.
 *
 * Additive contract: nothing here writes anything until the user acts; with
 * no keys the router chain is byte-identical to v2.1.0. Keys go through
 * SecureStore (Android Keystore AES-256/GCM) and are never rendered in full,
 * logged, or included in any payload.
 */
class CloudAiActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this, SecureStore(this))
        settings.runModeMigration()
        val materialYou = settings.materialYou()
        setContent {
            CometCloudTheme(materialYou) {
                CloudAiScreen(
                    settings = settings,
                    onBack = { finish() },
                    onOpenGuide = { openGuide() }
                )
            }
        }
    }

    private fun openGuide() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Bilal140202/comet-x-browser/blob/main/docs/ai/CLOUD_AI.md")))
        }
    }
}

// ------------------------------------------------------------------ specs

/** Static per-provider presentation (id/order shared with SettingsRepository). */
data class ProviderSpec(
    val id: String,
    val name: String,
    val blurb: String,
    val keyHint: String,
    val supportsBaseUrl: Boolean = false
)

val PROVIDER_SPECS = listOf(
    ProviderSpec(
        "nvidia", "NVIDIA NIM",
        "build.nvidia.com — 80+ hosted open models (GPT-OSS, Nemotron, Llama, Gemma, Kimi). Free developer credits with an nvapi- key. Endpoint + catalog verified live in v2.2.0.",
        "nvapi-…"
    ),
    ProviderSpec(
        "openrouter", "OpenRouter",
        "One key for 300+ models with per-model pricing; many :free models for light use.",
        "sk-or-v1-…"
    ),
    ProviderSpec(
        "groq", "Groq",
        "Ultra-fast LPU inference for open models.",
        "gsk_…"
    ),
    ProviderSpec(
        "huggingface", "Hugging Face",
        "Serverless inference through the HF router.",
        "hf_…"
    ),
    ProviderSpec(
        "custom", "Custom / OpenAI-compatible",
        "Any OpenAI-compatible endpoint: Ollama, LM Studio, vLLM, or an aggregator. Set its base URL and key.",
        "provider-specific key", supportsBaseUrl = true
    )
)

private fun cloudProvider(id: String, settings: SettingsRepository): OpenAICompatibleProvider = when (id) {
    "groq" -> GroqProvider({ settings.apiKey(id) })
    "openrouter" -> OpenRouterProvider({ settings.apiKey(id) })
    "nvidia" -> NvidiaProvider({ settings.apiKey(id) })
    "huggingface" -> HuggingFaceProvider({ settings.apiKey(id) })
    else -> CustomOpenAIProvider({ settings.apiKey(id) })
}

/** Mutable per-provider UI state (Compose-observable holders). */
class ProviderUi(val id: String) {
    var hasKey by mutableStateOf(false)
    var keyMasked by mutableStateOf("")
    var enabled by mutableStateOf(false)
    var chainPos by mutableStateOf(0)
    var mode by mutableStateOf(SettingsRepository.ModelMode.AUTO)
    var manualModel by mutableStateOf("")
    var baseUrlInput by mutableStateOf("")
    var keyInput by mutableStateOf("")
    var reveal by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var testResult by mutableStateOf("")
    var testOk by mutableStateOf<Boolean?>(null)
    var fetchedIds by mutableStateOf<List<String>>(emptyList())
}

// ------------------------------------------------------------------ screen

@Composable
fun CloudAiScreen(settings: SettingsRepository, onBack: () -> Unit, onOpenGuide: () -> Unit) {
    val scope = rememberCoroutineScope()

    fun refresh(providers: MutableList<ProviderUi>) {
        val order = settings.chainOrder()
        for (p in providers) {
            val key = settings.apiKey(p.id)
            p.hasKey = !key.isNullOrBlank()
            p.keyMasked = key?.let { KeyFormat.mask(it) } ?: ""
            p.enabled = settings.providerEnabled(p.id) && p.hasKey
            p.chainPos = order.indexOf(p.id) + 1
            p.mode = settings.modelMode(p.id)
            p.manualModel = settings.modelFor(p.id, ModelRouter.Role.AGENT) ?: ""
            if (p.id == "custom") p.baseUrlInput = settings.baseUrl("custom") ?: ""
            val last = settings.lastTest(p.id)
            if (last != null && p.testResult.isEmpty()) {
                val parts = last.split("|")
                p.testOk = parts.firstOrNull() == "ok"
                p.testResult = parts.getOrNull(2) ?: ""
            }
        }
    }

    val providers = remember {
        mutableStateListOf<ProviderUi>().apply {
            PROVIDER_SPECS.forEach { add(ProviderUi(it.id)) }
        }
    }
    remember { refresh(providers); true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Cloud AI") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh(providers) }) {
                        Icon(Icons.Filled.Refresh, "Reload")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { IntroHeader() }
            items(providers, key = { it.id }) { p ->
                val spec = PROVIDER_SPECS.first { it.id == p.id }
                ProviderCard(
                    spec = spec,
                    ui = p,
                    onToggleEnabled = { on ->
                        settings.setProviderEnabled(p.id, on)
                        p.enabled = on && p.hasKey
                    },
                    onMove = { dir ->
                        settings.moveInChain(p.id, dir)
                        refresh(providers)
                    },
                    onSaveKey = { key ->
                        settings.setApiKey(p.id, key)
                        settings.setLastTest(p.id, false, "key changed — run Test again")
                        refresh(providers)
                        p.testResult = if (key.isBlank()) "Key removed" else "Key saved — run Test"
                        p.testOk = null
                    },
                    onSaveBaseUrl = { url ->
                        settings.setBaseUrl("custom", url.takeIf { it.isNotBlank() }?.let { UrlNormalizer.normalize(it) })
                        refresh(providers)
                    },
                    onModeChange = { m ->
                        settings.setModelMode(p.id, m)
                        p.mode = m
                    },
                    onManualModelChange = { m ->
                        settings.setModel(p.id, ModelRouter.Role.AGENT, m)
                        p.manualModel = m
                    },
                    onTest = {
                        p.busy = true
                        p.testResult = "Testing…"
                        scope.launch {
                            val result = testProvider(settings, p.id, p.manualModel)
                            p.testOk = result.first
                            p.testResult = result.second
                            p.fetchedIds = result.third
                            p.busy = false
                        }
                    }
                )
            }
            item {
                DiscordCard(settings = settings, onOpenGuide = onOpenGuide)
            }
            item { FooterNote(onOpenGuide) }
        }
    }
}

/** Live provider test: catalog fetch + tiny completion. Runs on Dispatchers.IO. */
private suspend fun testProvider(
    settings: SettingsRepository,
    id: String,
    manualModel: String
): Triple<Boolean, String, List<String>> = withContext(Dispatchers.IO) {
    val provider = cloudProvider(id, settings)
    if (settings.apiKey(id).isNullOrBlank()) {
        return@withContext Triple(false, "No key saved for this provider", emptyList())
    }
    try {
        val ids = provider.listModels()
        val model = manualModel.ifBlank {
            ModelRouter.defaultModelFor(id, ModelRouter.Role.AGENT)
        }
        val latency = provider.ping(model)
        val okMsg = "OK · $latency ms · ${ids.size} models · tested with $model"
        settings.setLastTest(id, true, okMsg)
        Triple(true, okMsg, ids)
    } catch (e: Exception) {
        val msg = (e.message ?: e.javaClass.simpleName).take(180)
        settings.setLastTest(id, false, msg)
        Triple(false, "Failed — $msg", emptyList())
    }
}

// ------------------------------------------------------------------ cards

@Composable
private fun IntroHeader() {
    Column {
        Text(
            "Your personal API keys, one screen",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Paste a key, press Test, and Comet-X verifies the endpoint live — base URLs and " +
                "model catalogs are pre-configured and were validated against the real services. " +
                "Keys are encrypted with the Android Keystore, shown masked only, and never leave " +
                "the device except to call the provider you chose. Providers run in your order: " +
                "if one fails, the next takes over automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProviderCard(
    spec: ProviderSpec,
    ui: ProviderUi,
    onToggleEnabled: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onSaveKey: (String) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onModeChange: (SettingsRepository.ModelMode) -> Unit,
    onManualModelChange: (String) -> Unit,
    onTest: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = cs.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            spec.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusDot(ui.hasKey, ui.enabled, ui.testOk)
                    }
                    Text(
                        spec.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                Switch(checked = ui.enabled, onCheckedChange = onToggleEnabled, enabled = ui.hasKey)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stateLine = when {
                    ui.busy -> "Testing…"
                    ui.hasKey -> "Key ${ui.keyMasked}"
                    else -> "No key yet"
                }
                Text(
                    stateLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.hasKey) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onMove(-1) }, enabled = ui.chainPos > 1) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                }
                Text("#${ui.chainPos}", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { onMove(1) }, enabled = ui.chainPos < 5) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                }
                TextButton(onClick = { ui.expanded = !ui.expanded }) {
                    Text(if (ui.expanded) "Hide" else "Configure")
                }
            }

            AnimatedVisibility(visible = ui.expanded) {
                Column {
                    if (spec.supportsBaseUrl) {
                        OutlinedTextField(
                            value = ui.baseUrlInput,
                            onValueChange = { ui.baseUrlInput = it },
                            label = { Text("Base URL (OpenAI-compatible)") },
                            placeholder = { Text("https://host:port/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { onSaveBaseUrl(ui.baseUrlInput) }) { Text("Save base URL") }
                    }
                    OutlinedTextField(
                        value = ui.keyInput,
                        onValueChange = { ui.keyInput = it },
                        label = { Text("API key (${spec.keyHint})") },
                        singleLine = true,
                        isError = KeyFormat.warning(spec.id, ui.keyInput) != null && ui.keyInput.isNotBlank(),
                        supportingText = {
                            val w = KeyFormat.warning(spec.id, ui.keyInput)
                            if (w != null && ui.keyInput.isNotBlank()) Text(w)
                        },
                        visualTransformation = if (ui.reveal) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { ui.reveal = !ui.reveal }) {
                                Text(if (ui.reveal) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = { onSaveKey(ui.keyInput.trim()); ui.keyInput = "" },
                            enabled = ui.keyInput.isNotBlank()
                        ) { Text("Save key") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { onSaveKey("") },
                            enabled = ui.hasKey
                        ) { Text("Remove") }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Model selection", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = ui.mode == SettingsRepository.ModelMode.AUTO,
                            onClick = { onModeChange(SettingsRepository.ModelMode.AUTO) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("Auto") }
                        SegmentedButton(
                            selected = ui.mode == SettingsRepository.ModelMode.MANUAL,
                            onClick = { onModeChange(SettingsRepository.ModelMode.MANUAL) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("Manual") }
                    }
                    if (ui.mode == SettingsRepository.ModelMode.MANUAL) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = ui.manualModel,
                            onValueChange = onManualModelChange,
                            label = { Text("Model id override") },
                            placeholder = { Text("e.g. ${ModelRouter.defaultModelFor(spec.id, ModelRouter.Role.AGENT)}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (ui.fetchedIds.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ui.fetchedIds.take(20)) { mid ->
                                    AssistChip(onClick = { onManualModelChange(mid) }, label = { Text(mid, maxLines = 1) })
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onTest, enabled = !ui.busy && ui.hasKey) {
                            Icon(Icons.Filled.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Test connection")
                        }
                        if (ui.busy) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (ui.testResult.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when (ui.testOk) {
                                true -> cs.primaryContainer
                                false -> cs.errorContainer
                                null -> cs.surfaceVariant
                            }
                        ) {
                            Text(
                                ui.testResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (ui.testOk) {
                                    true -> cs.onPrimaryContainer
                                    false -> cs.onErrorContainer
                                    null -> cs.onSurfaceVariant
                                },
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(hasKey: Boolean, enabled: Boolean, testOk: Boolean?) {
    val color = when {
        !hasKey -> MaterialTheme.colorScheme.outline
        enabled && testOk == true -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.width(10.dp).height(10.dp)) {}
}

@Composable
private fun FooterNote(onOpenGuide: () -> Unit) {
    Column {
        Text(
            "Privacy: keys are encrypted at rest (Android Keystore), excluded from backups, " +
                "and used only to call the provider you configured. Diagnostics and logs never contain them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenGuide) { Text("Setup guide: docs/ai/CLOUD_AI.md") }
    }
}
