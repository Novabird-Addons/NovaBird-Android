package org.novabird.mail.protection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.novabird.feature.ai.AiVectorBackend
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.protection.ProtectionAi
import org.novabird.feature.vectors.ai.MailPrompts

/**
 * Facts about a mail from the language model of the NovaBird Helper (the same instruction as the desktop,
 * `MailPrompts.phish`, variant p1): who the mail claims to be, what it asks for, whether it presses. Only with a paired
 * Helper whose chat is allowed; no cloud. `injection` is rule-based, like on the desktop.
 */
class HelperProtectionAi(
    private val backend: AiVectorBackend,
    private val aiSettings: AiSettingsRepository,
) : ProtectionAi {

    override fun isAvailable(): Boolean = backend.isHelperPaired() && aiSettings.settings.value.helperChat

    override suspend fun extract(header: String, text: String): String? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null
        val client = backend.helper(TIMEOUT_MS) ?: return@withContext null
        val answer = client.chatText(
            MailPrompts.phish(header, text.take(MailPrompts.PHISH_CHARS)),
            MailPrompts.PHISH_MAX_NEW,
        )
        val from = answer.indexOf('{')
        val to = answer.lastIndexOf('}')
        if (from < 0 || to <= from) return@withContext null
        val facts = runCatching { JSONObject(answer.substring(from, to + 1)) }.getOrNull() ?: return@withContext null
        facts.put("ok", true)
        facts.put("injection", MailPrompts.suspicious(text))
        facts.put("model", "helper")
        facts.toString()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}
