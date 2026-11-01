package org.novabird.feature.ai.assist

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.novabird.feature.ai.AiVectorBackend
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.search.MailForAi
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.NovaBirdMailTexts
import org.novabird.feature.vectors.HelperClient
import org.novabird.feature.vectors.HelperException
import org.novabird.feature.vectors.ai.AiText
import org.novabird.feature.vectors.ai.Day
import org.novabird.feature.vectors.ai.DigestEntry
import org.novabird.feature.vectors.ai.HtmlText
import org.novabird.feature.vectors.ai.MailPrompts

/** Overview of new mails: one entry per mail (same order as [mails]), the text like the desktop, all found. */
data class Overview(
    val entries: List<DigestEntry>,
    val mails: List<MailForAi>,
    val total: Int,
    val suspicious: Boolean,
    val text: String,
)

/**
 * AI summary and "Was gibt's Neues" with the language model of the NovaBird Helper, with the instructions of the
 * desktop
 * (org.novabird.feature.vectors.ai.MailPrompts). Sorted mails are kept in [cache], so the overview is fast again.
 */
class AiAssistant(
    private val backend: AiVectorBackend,
    private val settings: AiSettingsRepository,
    private val mailTexts: NovaBirdMailTexts,
    private val cache: DigestCache,
) {
    /** True if a Helper is paired and its language model may be used. */
    fun available() = backend.isHelperPaired() && settings.settings.value.helperChat

    suspend fun mail(location: MessageLocation): MailForAi? = mailTexts.mail(location)

    /** Mail text for the model and for reading aloud: only the new part, like the desktop. */
    fun textOf(mail: MailForAi): String = if (mail.plain.isNotBlank()) {
        AiText.mailText(mail.plain, mail.subject)
    } else {
        AiText.mailText(HtmlText.toText(mail.html), mail.subject, newPart = HtmlText.toText(mail.html, onlyNew = true))
    }

    /** Summary of [mail] in [uiLang]; [onText] gets the text so far. */
    suspend fun summarize(
        mail: MailForAi,
        uiLang: String,
        cancelled: () -> Boolean = { false },
        onText: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val client = client()
        val prompt = MailPrompts.summary(uiLang, inMailLanguage = false, header(mail), textOf(mail))
        client.chatText(prompt.messages, MailPrompts.SUMMARY_MAX_NEW, prompt.meta, cancelled, onText)
    }

    /** Sorts the newest inbox mails one by one (Helper) and builds the overview; [onProgress] gets done/all. */
    suspend fun overview(
        uiLang: String,
        hours: Int,
        unreadOnly: Boolean,
        cancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Overview = withContext(Dispatchers.IO) {
        val recent = mailTexts.recentMails(hours, unreadOnly, MAX_MAILS)
        val client = if (recent.mails.isEmpty()) null else client()
        var suspicious = false
        val entries = recent.mails.mapIndexed { i, mail ->
            if (cancelled()) throw CancellationException("cancelled")
            onProgress(i, recent.mails.size)
            val text = textOf(mail)
            suspicious = suspicious || MailPrompts.suspicious(text)
            val sorted = if (text.isBlank() || client == null) null else digest(client, uiLang, mail, text, cancelled)
            sorted ?: DigestEntry("info", mail.from, mail.subject, emptyList())
        }
        onProgress(recent.mails.size, recent.mails.size)
        cache.save()
        Overview(entries, recent.mails, recent.total, suspicious, MailPrompts.overviewText(uiLang, entries))
    }

    @Suppress("SwallowedException") // a failed mail falls back to sender and subject
    private fun digest(
        client: HelperClient,
        uiLang: String,
        mail: MailForAi,
        text: String,
        cancelled: () -> Boolean,
    ): DigestEntry? {
        val key = "$uiLang|${mail.headerMessageId}|${HelperClient.textHash(text)}"
        cache.get(key)?.let { return it }
        return try {
            val prompt = MailPrompts.digest(uiLang, dayOf(mail.date), header(mail), text.take(MailPrompts.DIGEST_CHARS))
            MailPrompts.parseDigest(client.chatText(prompt, MailPrompts.DIGEST_MAX_NEW, cancelled = cancelled))
                .also { cache.put(key, it) }
        } catch (e: IllegalArgumentException) {
            null // no JSON in the answer: the overview shows sender and subject
        } catch (e: HelperException) {
            // wrong code, Helper gone or stopped: stop; a model error for one mail: sender and subject
            if (e.message == "code" || e.message == "cancelled" || e.message.orEmpty().startsWith("http")) throw e
            null
        } // IOException (Helper not reachable) stops the overview
    }

    private fun client(): HelperClient {
        if (!available()) throw HelperException("helper")
        return backend.helper(CHAT_TIMEOUT_MS) ?: throw HelperException("helper")
    }

    companion object {
        /** At most 40 mails per overview (fits safely in the context with their sorted entries, like the desktop). */
        const val MAX_MAILS = 40
        private const val CHAT_TIMEOUT_MS = 120_000

        fun uiLang(): String = Locale.getDefault().language.takeIf { it == "de" } ?: "en"

        fun header(mail: MailForAi): String {
            val date = if (mail.date > 0) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(mail.date))
            } else {
                ""
            }
            return MailPrompts.header(mail.subject, mail.from, date)
        }

        fun dayOf(millis: Long): Day {
            val c = Calendar.getInstance().apply {
                timeInMillis = if (millis >
                    0
                ) {
                    millis
                } else {
                    System.currentTimeMillis()
                }
            }
            return Day(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }
    }
}
