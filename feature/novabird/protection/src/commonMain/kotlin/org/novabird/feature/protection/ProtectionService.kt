package org.novabird.feature.protection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.novabird.feature.search.K9MailStore
import org.novabird.feature.search.MailForCheck
import org.novabird.feature.search.NovaBirdMailTexts
import org.novabird.feature.search.unpackK9Addresses

/**
 * The phishing protection: checks new mails after a sync ([checkNewMails]) and mails when they are opened
 * ([verdictOnOpen]), and keeps the results in [ProtectionStore].
 *
 * Nothing runs while [ProtectionSettings.enabled] is false. Only mails that arrive after switching on are checked in
 * the background (watermark per account); older mails only when they are opened.
 *
 * @param engine the add-on core in QuickJS; created on first use and kept (boot ≈ 0.2 s).
 * @param dispatcher confines the store and all K-9 reads to one thread at a time.
 * @param onEnabled called after switching on (the app schedules the first background check there).
 */
@Suppress("LongParameterList")
class ProtectionService(
    private val engine: suspend () -> ProtectionEngine,
    private val texts: NovaBirdMailTexts,
    private val mailStore: K9MailStore,
    private val store: ProtectionStore,
    private val settings: ProtectionSettings,
    private val contacts: ContactsSource,
    private val dispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val onEnabled: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var contextLoadedAt = Long.MIN_VALUE
    private val _revision = MutableStateFlow(0L)

    val isEnabled: Boolean get() = settings.enabled

    /** Counts up whenever stored results change (new check, trust, untrust); the message list refreshes its badges. */
    val revision: StateFlow<Long> = _revision

    val trustedSenders: List<TrustedSender> get() = settings.trustedSenders

    /** Switches the protection on after the user accepted notice [consentVersion]. Existing mail stays unchecked. */
    suspend fun enable(consentVersion: Int) = locked {
        settings.setEnabled(true, consentVersion, now())
        store.clearWatermarks()
        for (account in mailStore.accounts()) store.setWatermark(account.uuid, maxMessageId(account.uuid))
        contextLoadedAt = Long.MIN_VALUE
        _revision.value++
        onEnabled()
    }

    /** Explanations of a stored result in [language] (`de`/`en`), strongest first. */
    suspend fun reasons(
        verdict: StoredVerdict,
        language: String,
    ): List<Reason> = engine().reasons(verdict.verdict, language)

    /**
     * "Absender vertrauen" from the warning of a mail. Refused for a mail rated dangerous (add-on 0.9.1: only via the
     * settings). On success the sender's stored results are dropped, so the badge goes and the next open checks again.
     */
    suspend fun trustSenderOf(accountUuid: String, k9Id: Long, language: String): TrustOutcome {
        if (!settings.enabled) return TrustOutcome.NO_VERDICT
        return locked {
            val stored = store.get(accountUuid, k9Id) ?: return@locked TrustOutcome.NO_VERDICT
            if (stored.level == ProtectionLevel.DANGEROUS) return@locked TrustOutcome.REFUSED_DANGEROUS
            if (stored.sender.isEmpty()) return@locked TrustOutcome.NO_VERDICT
            val reasons = engine().reasons(stored.verdict, language).map { it.text.take(MAX_REASON_CHARS) }
            addTrusted(
                TrustedSender(
                    address = stored.sender,
                    since = now(),
                    via = TrustedVia.BANNER,
                    score = stored.verdict.score,
                    level = stored.level,
                    reasons = reasons.take(MAX_REASONS),
                ),
            )
            TrustOutcome.TRUSTED
        }
    }

    /** Adds [sender] (typed in, or put back after an undo). */
    suspend fun trust(sender: TrustedSender) = locked { addTrusted(sender) }

    /** Removes [address] from the trusted list; its mails are checked again when opened. */
    suspend fun untrust(address: String) = locked {
        val key = address.trim().lowercase()
        settings.updateTrustedSenders(settings.trustedSenders.filter { it.address != key })
        store.deleteBySender(key)
        _revision.value++
    }

    private fun addTrusted(sender: TrustedSender) {
        val key = sender.address.trim().lowercase()
        val others = settings.trustedSenders.filter { it.address != key }
        settings.updateTrustedSenders(others + sender.copy(address = key))
        store.deleteBySender(key)
        _revision.value++
    }

    suspend fun disable() = locked {
        settings.setEnabled(false, settings.consentVersion, settings.consentAt)
    }

    /** Stored result of an opened mail; checks it first if it has none (or one from older rules). */
    suspend fun verdictOnOpen(accountUuid: String, k9Id: Long): StoredVerdict? {
        if (!settings.enabled) return null
        return locked {
            store.get(accountUuid, k9Id)?.takeIf { it.rulesVersion == RULES_VERSION } ?: check(accountUuid, k9Id)
        }
    }

    /** Stored result without checking (`null` if none or protection is off). */
    suspend fun storedVerdict(accountUuid: String, k9Id: Long): StoredVerdict? {
        if (!settings.enabled) return null
        return locked { store.get(accountUuid, k9Id) }
    }

    /** Levels of messages that have a result, for the message list. */
    suspend fun levels(accountUuid: String, k9Ids: Collection<Long>): Map<Long, ProtectionLevel> {
        if (!settings.enabled) return emptyMap()
        return locked { store.levels(accountUuid, k9Ids) }
    }

    /** Checks inbox mails that arrived after switching on and have no result yet. Returns the number checked. */
    suspend fun checkNewMails(): Int {
        if (!settings.enabled) return 0
        return locked {
            var count = 0
            for (account in mailStore.accounts()) {
                val watermark = store.watermark(account.uuid)
                if (watermark == null) {
                    // account added after switching on: start from now
                    store.setWatermark(account.uuid, maxMessageId(account.uuid))
                    continue
                }
                val ids = newInboxIds(account.uuid, watermark)
                val done = store.checkedIds(account.uuid, ids, RULES_VERSION)
                for (id in ids) {
                    if (id !in done && check(account.uuid, id) != null) count++
                }
            }
            count
        }
    }

    // ------------------------------------------------------------ check

    private suspend fun check(accountUuid: String, k9Id: Long): StoredVerdict? {
        val mail = texts.mailForCheck(accountUuid, k9Id) ?: return null
        val js = engine()
        refreshContext(js)
        val config = JsonObject(
            mapOf("trustedSenders" to JsonArray(settings.trustedSenders.map { JsonPrimitive(it.address) })),
        )
        val features = js.features(input(mail), config)
        val context = mutableMapOf<String, JsonElement>(
            "ownDomains" to JsonArray(ownDomains().map(::JsonPrimitive)),
        )
        threadFound(features)?.let { context["threadFound"] = JsonPrimitive(it) }
        var verdict = js.analyze(features, JsonObject(context), config)
        if (js.relatedCouldHelp(verdict)) {
            findRelated(js, mail, features)?.let { related ->
                context["related"] = related
                verdict = js.analyze(features, JsonObject(context), config)
            }
        }
        val sender = features["from"]?.let { from ->
            (from as? JsonObject)?.get("address")?.jsonPrimitive?.content
        }.orEmpty()
        val record = StoredVerdict(
            accountUuid = accountUuid,
            k9Id = k9Id,
            folderId = mail.location.folderId,
            uid = mail.location.uid,
            headerMessageId = mail.headerMessageId,
            sender = sender,
            verdict = verdict,
            rulesVersion = RULES_VERSION,
            checkedAt = now(),
        )
        store.save(record)
        _revision.value++
        return record
    }

    /** Contacts and sender history in the engine, refreshed at most every [CONTEXT_MAX_AGE] ms. */
    private suspend fun refreshContext(js: ProtectionEngine) {
        val time = now()
        if (contextLoadedAt != Long.MIN_VALUE && time - contextLoadedAt < CONTEXT_MAX_AGE) return
        js.setContacts(contacts.contacts())
        js.setSenderIndex(SenderIndexBuilder(mailStore).build(time, settings.historyMonths, store.flaggedSenders()))
        contextLoadedAt = time
    }

    private fun input(mail: MailForCheck) = JsonObject(
        mapOf(
            "headers" to JsonObject(mail.headers.mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }),
            "parts" to JsonArray(
                mail.parts.map { part ->
                    JsonObject(
                        buildMap {
                            put("contentType", JsonPrimitive(part.contentType))
                            part.name?.let { put("name", JsonPrimitive(it)) }
                            part.body?.let { put("body", JsonPrimitive(it)) }
                        },
                    )
                },
            ),
        ),
    )

    private fun ownDomains(): List<String> = mailStore.accounts()
        .flatMap { it.emails }
        .mapNotNull { email -> email.substringAfterLast('@', "").lowercase().takeIf { it.isNotEmpty() } }
        .distinct()

    /** For replies ("Re:"): is the mail they answer in one of the mailboxes? `null` if it is no reply. */
    private fun threadFound(features: JsonObject): Boolean? {
        if (features["isReplySubject"]?.jsonPrimitive?.booleanOrNull != true) return null
        val inReplyTo = features["inReplyTo"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val references = features["references"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val ids = (inReplyTo + references.takeLast(THREAD_REFERENCES)).map { it.trim('<', '>', ' ') }.distinct()
        if (ids.isEmpty()) return null
        return mailStore.accounts().any { account ->
            mailStore.open(account.uuid)?.use { db ->
                ids.any { id ->
                    db.query(
                        "SELECT 1 FROM messages WHERE message_id IN (?, ?) LIMIT 1",
                        listOf(id, "<$id>"),
                    ).isNotEmpty()
                }
            } ?: false
        }
    }

    /**
     * Relation to an earlier mail like findRelated() of the add-on: an order, shipment or invoice number of this mail
     * in the subject of an earlier mail (up to [RELATED_DAYS] days back) that came from a sent folder, a contact or an
     * established sender. Subject only (the add-on searches bodies only in local accounts); no DKIM branch.
     */
    private suspend fun findRelated(js: ProtectionEngine, mail: MailForCheck, features: JsonObject): JsonObject? {
        val subject = features["subject"]?.jsonPrimitive?.content.orEmpty()
        val text = features["text"]?.jsonPrimitive?.content.orEmpty()
        val anchors = js.anchors(subject, text)
        if (anchors.isEmpty()) return null
        val date = mail.date.takeIf { it > 0 } ?: now()
        val from = date - RELATED_DAYS * DAY_MILLIS
        for (anchor in anchors) {
            val pattern = "%" + anchor.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            for (account in mailStore.accounts()) {
                val rows = mailStore.open(account.uuid)?.use { db ->
                    db.query(
                        """
                        SELECT m.id, m.date, m.sender_list, f.type FROM messages m JOIN folders f ON f.id = m.folder_id
                        WHERE m.deleted = 0 AND m.empty = 0 AND m.subject LIKE ? ESCAPE '\' AND m.date >= ? AND m.date < ?
                        ORDER BY m.date DESC LIMIT $RELATED_CANDIDATES
                        """.trimIndent(),
                        listOf(pattern, from.toString(), date.toString()),
                    )
                }.orEmpty()
                for (row in rows) {
                    val id = row[0] as Long
                    if (account.uuid == mail.accountUuid && id == mail.k9Id) continue
                    val type = (row[3] as String?)?.lowercase()
                    if (type in RELATED_SKIPPED_TYPES) continue
                    if (store.get(account.uuid, id)?.level?.let { it != ProtectionLevel.NORMAL } == true) continue
                    val author = unpackK9Addresses(row[2] as String?).firstOrNull()?.email.orEmpty()
                    if (js.trustedEarlier(author, fromSentFolder = type == "sent")) {
                        return JsonObject(
                            mapOf(
                                "anchor" to JsonPrimitive(anchor),
                                "date" to ((row[1] as Long?)?.let { JsonPrimitive(it) } ?: JsonNull),
                            ),
                        )
                    }
                }
            }
        }
        return null
    }

    private fun maxMessageId(accountUuid: String): Long = mailStore.open(accountUuid)?.use { db ->
        db.query("SELECT ifnull(max(id), 0) FROM messages").firstOrNull()?.get(0) as Long?
    } ?: 0L

    private fun newInboxIds(accountUuid: String, watermark: Long): List<Long> = mailStore.open(accountUuid)?.use { db ->
        db.query(
            """
            SELECT m.id FROM messages m JOIN folders f ON f.id = m.folder_id
            WHERE m.id > ? AND m.deleted = 0 AND m.empty = 0 AND m.uid IS NOT NULL AND lower(f.type) = 'inbox'
            ORDER BY m.id LIMIT $NEW_MAILS_PER_RUN
            """.trimIndent(),
            listOf(watermark.toString()),
        ).map { it[0] as Long }
    }.orEmpty()

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { withContext(dispatcher) { block() } }

    companion object {
        /** Version of the rules (add-on core + this check); results of other versions are checked again on open. */
        const val RULES_VERSION = "addon-0.9.1+android-1"

        private const val CONTEXT_MAX_AGE = 10 * 60_000L
        private const val MAX_REASONS = 8
        private const val MAX_REASON_CHARS = 160
        private const val THREAD_REFERENCES = 3
        private const val RELATED_DAYS = 180L
        private const val RELATED_CANDIDATES = 20
        private const val DAY_MILLIS = 86_400_000L
        private const val NEW_MAILS_PER_RUN = 200
        private val RELATED_SKIPPED_TYPES = setOf("spam", "trash", "drafts", "outbox")
    }
}

/** Result of [ProtectionService.trustSenderOf]. */
enum class TrustOutcome {
    TRUSTED,

    /** The mail is rated dangerous: trusting only via the settings. */
    REFUSED_DANGEROUS,

    /** No stored result for the mail (or protection off). */
    NO_VERDICT,
}
