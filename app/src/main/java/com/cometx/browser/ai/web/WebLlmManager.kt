package com.cometx.browser.ai.web

import android.content.Context
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.security.SecureStore
import kotlinx.coroutines.flow.StateFlow

/**
 * WebLlmManager (v2.1.0) — app-scoped owner of the in-browser transformer
 * stack, mirroring [com.cometx.browser.ai.local.LocalModelManager]'s role for
 * the llama.cpp stack. One runtime + one provider for the whole process, so
 * the resident pipeline survives Settings round-trips and activity recreation,
 * and the agent's router and the Settings UI observe the same state.
 *
 * Selection semantics: the selected model id is persisted in settings and
 * resolved through [WebLlmCatalog] (unknown ids — e.g. after a future catalog
 * change — resolve to null → provider simply not ready; nothing crashes).
 */
class WebLlmManager(
    context: Context,
    val engine: WebLlmEngine = WebLlmRuntime(context.applicationContext),
    val settings: SettingsRepository =
        SettingsRepository(context, SecureStore(context)),
) {

    val provider = TransformersWebProvider(engine) { selectedModel() }

    /** Progress mirror for the Settings UI (load %, phases, errors). */
    val progress: StateFlow<WebLoadProgress> get() = engine.progress

    /** The user-selected web model, or null when none/unknown. */
    fun selectedModel(): WebLlmCatalog.WebModel? =
        WebLlmCatalog.byIdOrNull(settings.webModelId())

    /** Persist the selection (null clears it → provider leaves the router chain). */
    fun select(modelId: String?) {
        settings.setWebModelId(modelId)
    }

    /** Honest availability: selected model + healthy runtime. */
    fun isAvailable(): Boolean = provider.isReady()

    /** Free the renderer (Settings action / low-memory paths). Weights stay cached. */
    fun releaseMemory() = engine.release()

    /** Cooperative stop for a live generation. */
    fun cancel() = provider.cancel()
}
