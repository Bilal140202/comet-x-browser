package com.cometx.browser.perception

import com.cometx.browser.ai.SettingsRepository

/**
 * VisionPolicy — decides WHEN a screenshot is actually worth a VLM call.
 * (browser-use ecosystem lesson: screenshots every step is expensive.)
 * Triggers: user "always", DOM ambiguity, last-action failure, suspected
 * challenge, or explicit agent request.
 */
class VisionPolicy(private val settings: SettingsRepository) {

    /** Returns true if a screenshot should be attached to this observation. */
    fun shouldCapture(
        obs: PageObservation,
        lastActionFailed: Boolean,
        agentRequestedVision: Boolean,
        stepIndex: Int
    ): Boolean {
        return when (settings.visionMode()) {
            SettingsRepository.VisionMode.OFF -> agentRequestedVision
            SettingsRepository.VisionMode.ALWAYS -> true
            SettingsRepository.VisionMode.AUTO -> agentRequestedVision ||
                lastActionFailed ||
                obs.challenge?.let { it.type != ChallengeResult.NONE } == true ||
                obs.elements.isEmpty() ||
                (obs.elements.size < 3 && obs.textSample.length < 60) ||
                looksCanvasHeavy(obs) ||
                stepIndex == 0 && obs.elements.size < 5
        }
    }

    /** Canvas games / maps / photo editors render few interactive DOM nodes. */
    private fun looksCanvasHeavy(obs: PageObservation): Boolean =
        obs.textSample.length < 120 && obs.viewportW > 0 && obs.elements.size < 8
}
