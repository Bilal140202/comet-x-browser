package com.cometx.browser.skills

import android.content.Context
import com.cometx.browser.util.Logx
import org.json.JSONObject
import java.io.File

/**
 * UserSkillStore — persistence for user-created skills (recorded or
 * /grill-me generated). One JSON file per skill under filesDir/skills_user/.
 *
 * Nothing here ever leaves the device; skills may contain typed text, so the
 * directory is app-private (Android default) and never exported implicitly —
 * "Export" in the UI copies to clipboard only when the user asks.
 */
class UserSkillStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "skills_user").apply { if (!exists()) mkdirs() }

    fun list(): List<RecordedSkill> =
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { RecordedSkill.parse(f.readText()) }
                    .onFailure { Logx.w("skill file ${f.name} unparseable: ${it.message}") }
                    .getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun byId(id: String): RecordedSkill? = list().firstOrNull { it.id == id }

    fun save(skill: RecordedSkill): Boolean {
        return try {
            val safeId = skill.id.replace(Regex("[^a-zA-Z0-9_-]"), "").take(64)
                .ifBlank { "skill-${System.currentTimeMillis()}" }
            val payload = skill.copy(id = safeId).toJson().toString(2)
            val tmp = File(dir, "$safeId.json.tmp")
            tmp.writeText(payload)
            val dst = File(dir, "$safeId.json")
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) dst.writeText(payload)   // rename can fail across odd FS states — write is the backstop
            true
        } catch (e: Exception) {
            Logx.e("skill save failed", e)
            false
        }
    }

    fun delete(id: String): Boolean {
        val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "").take(64)
        return File(dir, "$safeId.json").delete()
    }

    /** Records a successful replay (usage telemetry stays on-device). */
    fun markRun(id: String) {
        val s = byId(id) ?: return
        save(s.copy(lastRunAt = System.currentTimeMillis(), runCount = s.runCount + 1))
    }

    /** Merges edits from the review dialog: rebuild from edited JSON text. */
    fun saveFromJsonText(id: String, jsonText: String): RecordedSkill? {
        val parsed = RecordedSkill.parse(jsonText) ?: return null
        val merged = if (parsed.id != id) parsed.copy(id = id) else parsed
        return if (save(merged)) merged else null
    }

    companion object {
        /** Hard limits applied at record/replay/generation time. */
        const val MAX_STEPS = 200
        const val MAX_VALUE_LEN = 5000
        const val MAX_URL_LEN = 2048
    }
}
