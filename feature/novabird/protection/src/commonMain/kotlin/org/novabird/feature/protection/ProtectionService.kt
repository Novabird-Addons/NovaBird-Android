package org.novabird.feature.protection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import org.novabird.feature.search.MailPart
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
 * @param ai local language model for the grey zone, or `null`. After a sync it is asked inline; for an opened mail the
 *   rules answer at once and the model's facts follow in [scope] (the banner and the badge refresh via [revision]).
 * @param media reads QR codes and text in images, or `null` (images add nothing). After a sync everything is read
 *   before the result is stored; for an opened mail QR codes and attachments at once, the slower text reading (OCR)
 *   follows in [scope] like the model.
 * @param spamMover moves spam to the account's spam folder (only after a sync, never when a mail is opened), or `null`.
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
    private val ai: ProtectionAi? = null,
    private val scope: CoroutineScope? = null,
    private val media: MediaScanner? = null,
    private val spamMover: SpamMover? = null,
) {
    private val mutex = Mutex()
    private var contextLoadedAt = Long.MIN_VALUE

    /** After a failed model call (Helper not reachable) no mail is sent to the model until this time. */
    private var aiPausedUntil = 0L

    /** Mails whose model call is running (open path), so a second open does not ask twice. */
    private val aiInFlight = HashSet<Pair<String, Long>>()
    private val aiInFlightMutex = Mutex()

    /** Mails whose images and attachments are being read right now (guarded by [mutex]). */
    private val mediaRunning = HashSet<Pair<String, Long>>()
    private val _revision = MutableStateFlow(0L)

    val isEnabled: Boolean get() = settings.enabled

    /** Counts up whenever stored results change (new check, trust, untrust); the message list refreshes its badges. */
    val revision: StateFlow<Long> = _revision

    val trustedSenders: List<TrustedSender> get() = settings.trustedSenders

    val useAi: Boolean get() = settings.useAi

    /** A local language model is set up (Helper paired and its chat allowed). */
    val isAiAvailable: Boolean get() = ai?.isAvailable() == true

    fun updateUseAi(on: Boolean) = settings.updateUseAi(on)

    val scanImages: Boolean get() = settings.scanImages

    val spamSettings: SpamSettings get() = settings.spam

    /**
     * Changes the spam settings. Senders that became allowed lose their stored spam decisions, so the badge goes and the
     * next open checks again.
     */
    suspend fun updateSpam(transform: (SpamSettings) -> SpamSettings) = locked {
        val old = settings.spam
        val new = transform(old)
        settings.updateSpam(new)
        val added = new.allowed - old.allowed.toSet()
        if (added.isNotEmpty()) {
            for (sender in store.spamSenders()) {
                if (added.any { SpamRules.matches(it, sender) }) store.deleteBySender(sender)
            }
        }
        _revision.value++
    }

    /**
     * "Kein Spam" in the banner: the sender goes onto the allowed list (and off the blocked one); a mail in the spam
     * folder moves back to the inbox. Returns `false` if the mail has no result.
     */
    suspend fun notSpam(accountUuid: String, k9Id: Long): Boolean {
        val stored = locked { store.get(accountUuid, k9Id) } ?: return false
        val sender = stored.sender
        if (sender.isNotEmpty()) {
            updateSpam { spam ->
                spam.copy(allowed = (spam.allowed + sender).distinct(), blocked = spam.blocked - sender)
            }
        }
        val mail = locked { texts.mailForCheck(accountUuid, k9Id) }
        if (mail?.folderType == "spam") spamMover?.moveToInbox(accountUuid, mail.location.folderId, mail.location.uid)
        locked {
            store.get(accountUuid, k9Id)?.let { save(it.copy(spam = null)) }
        }
        return true
    }

    fun updateScanImages(on: Boolean) = settings.updateScanImages(on)

    /** Reads contacts and the sender history again on the next check (e.g. after the contacts permission was granted). */
    fun invalidateContext() {
        contextLoadedAt = Long.MIN_VALUE
    }

    /** Switches the protection on after the user accepted notice [consentVersion]. Existing mail stays unchecked. */
    suspend fun enable(consentVersion: Int) = locked {
        settings.setEnabled(true, consentVersion, now())
        store.clearWatermarks()
        for (account in mailStore.accounts()) store.setWatermark(account.uuid, maxMessageId(account.uuid), now())
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

    /**
     * Stored result of an opened mail; checks it first if it has none (or one from older rules). The language model, if
     * any, is asked afterwards in the background so the banner does not wait for it.
     */
    suspend fun verdictOnOpen(accountUuid: String, k9Id: Long): StoredVerdict? {
        if (!settings.enabled) return null
        val verdict = locked { store.get(accountUuid, k9Id)?.takeIf { it.rulesVersion == RULES_VERSION } }
            ?: run {
                val mail = locked { texts.mailForCheck(accountUuid, k9Id) } ?: return null
                val extras = guardedMedia(mail, previous = null, withOcr = false)
                locked { checkMail(mail, extras) }
            }
        if ((wantsOcr(verdict) || wantsAi(verdict)) && scope != null) {
            val key = accountUuid to k9Id
            val started = aiInFlightMutex.withLock { aiInFlight.add(key) }
            if (started) {
                scope.launch {
                    try {
                        refineWithImages(accountUuid, k9Id)
                        refineWithAi(accountUuid, k9Id)
                    } finally {
                        withContext(NonCancellable) { aiInFlightMutex.withLock { aiInFlight.remove(key) } }
                    }
                }
            }
        }
        return verdict
    }

    /** Reads the text in the images of a stored result that has not read it yet (OCR) and rates the mail again. */
    suspend fun refineWithImages(accountUuid: String, k9Id: Long) {
        val stored = locked { store.get(accountUuid, k9Id) } ?: return
        if (!wantsOcr(stored)) return
        val mail = locked { texts.mailForCheck(accountUuid, k9Id) } ?: return
        val extras = guardedMedia(mail, stored.extras, withOcr = true)
        locked {
            if (store.get(accountUuid, k9Id)?.let(::wantsOcr) == true) checkMail(mail, extras)
        }
    }

    private fun wantsOcr(verdict: StoredVerdict): Boolean =
        media != null && settings.scanImages && verdict.extras?.ocrDone == false

    /** Asks the language model about a stored grey-zone result and rates the mail again with its facts. */
    suspend fun refineWithAi(accountUuid: String, k9Id: Long) {
        val model = ai ?: return
        val prepared = locked {
            val stored = store.get(accountUuid, k9Id) ?: return@locked null
            if (!wantsAi(stored)) return@locked null
            val mail = texts.mailForCheck(accountUuid, k9Id) ?: return@locked null
            evaluate(mail, stored.extras)
        } ?: return
        // the model call is slow and may use the network: outside the lock
        val answer = askModel(model, prepared) ?: return
        locked {
            if (store.get(accountUuid, k9Id)?.let(::wantsAi) != true) return@locked
            save(prepared.withAi(engine(), answer))
        }
    }

    /**
     * The model's answer for [prepared], or `null` if the model could not be reached: then nothing is stored (the mail
     * is asked again later) and the model is left alone for [AI_PAUSE_MILLIS].
     */
    private suspend fun askModel(model: ProtectionAi, prepared: Evaluation): String? {
        if (now() < aiPausedUntil) return null
        return try {
            model.extract(prepared.header, prepared.text)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            aiPausedUntil = now() + AI_PAUSE_MILLIS
            null
        }
    }

    /** Grey zone of the add-on: not dangerous, at least one signal, sender not trusted, model not asked yet. */
    private fun wantsAi(verdict: StoredVerdict): Boolean = ai != null && settings.useAi && ai.isAvailable() &&
        verdict.level != ProtectionLevel.DANGEROUS && verdict.verdict.signals.isNotEmpty() &&
        !verdict.verdict.json.containsKey(AI_CHECKED) &&
        settings.trustedSenders.none { it.address == verdict.sender }

    /** Stored result without checking (`null` if none or protection is off). */
    suspend fun storedVerdict(accountUuid: String, k9Id: Long): StoredVerdict? {
        if (!settings.enabled) return null
        return locked { store.get(accountUuid, k9Id) }
    }

    /** Level and spam mark of messages that have a result, for the message list (spam only while it is on). */
    suspend fun marks(accountUuid: String, k9Ids: Collection<Long>): Map<Long, ListMark> {
        if (!settings.enabled) return emptyMap()
        val spamOn = settings.spam.enabled
        return locked { store.marks(accountUuid, k9Ids) }.mapValues { (_, mark) ->
            if (spamOn) mark else mark.copy(spam = false)
        }
    }

    /** Levels of messages that have a result, for the message list. */
    suspend fun levels(accountUuid: String, k9Ids: Collection<Long>): Map<Long, ProtectionLevel> {
        if (!settings.enabled) return emptyMap()
        return locked { store.levels(accountUuid, k9Ids) }
    }

    /**
     * Checks inbox mails that arrived after switching on and have no result yet. Returns the number checked. The lock is
     * held per mail only, so an opened mail does not wait for the whole run (nor for the model).
     */
    suspend fun checkNewMails(): Int {
        if (!settings.enabled) return 0
        var count = 0
        // boot the engine and build the sender history now, not on the first open
        locked { refreshContext(engine()) }
        for (account in mailStore.accounts()) {
            val runStarted = now()
            val (watermark, since) = locked { accountProgress(account.uuid, runStarted) } ?: continue
            // a mail counts as new if it reached the server after the last run (a day of slack for clocks)
            val arrivedAfter = since - ARRIVAL_SLACK_MILLIS
            var after = watermark
            while (true) {
                val (rows, done) = locked {
                    val rows = newInboxMails(account.uuid, after)
                    rows to store.checkedIds(account.uuid, rows.map { it.first }, RULES_VERSION)
                }
                for ((id, arrived) in rows) {
                    // older mails loaded later ("all messages", sync range) are checked when opened, not here
                    if (id in done || arrived < arrivedAfter) continue
                    val mail = locked { texts.mailForCheck(account.uuid, id) } ?: continue
                    // images outside the lock: an opened mail need not wait for the text reading
                    val extras = guardedMedia(mail, previous = null, withOcr = true)
                    val record = locked { checkMail(mail, extras) }
                    count++
                    if (wantsAi(record)) refineWithAi(account.uuid, id)
                    moveIfSpam(account.uuid, id)
                }
                if (rows.isNotEmpty()) {
                    after = rows.last().first
                    locked { store.setWatermark(account.uuid, after, since) }
                }
                if (rows.size < NEW_MAILS_PER_RUN) break
            }
            locked { store.setWatermark(account.uuid, after, runStarted) }
        }
        return count
    }

    /**
     * Watermark and time of the last run of an account; `null` (nothing to do yet) for an account added after switching
     * on, which starts from now. Data from before the time was stored also starts from now, so a backlog of older mails
     * is never worked through in the background.
     */
    private fun accountProgress(accountUuid: String, now: Long): Pair<Long, Long>? {
        val watermark = store.watermark(accountUuid)
        if (watermark == null) {
            store.setWatermark(accountUuid, maxMessageId(accountUuid), now)
            return null
        }
        return watermark to (store.since(accountUuid) ?: now)
    }

    /**
     * [readMedia] with a mark that survives the app dying while reading: the next try of that mail leaves images and
     * attachments out (one bad image must not stop the app again and again). A mail being read in parallel (open and
     * sync at once) is left without media this time, without the mark.
     */
    private suspend fun guardedMedia(mail: MailForCheck, previous: MailExtras?, withOcr: Boolean): MailExtras {
        val key = mail.accountUuid to mail.k9Id
        val start = locked {
            when {
                key in mediaRunning -> MediaStart.BUSY

                store.mediaAttempted(mail.accountUuid, mail.k9Id) -> MediaStart.FAILED_BEFORE

                else -> {
                    store.markMediaAttempt(mail.accountUuid, mail.k9Id)
                    mediaRunning += key
                    MediaStart.READ
                }
            }
        }
        return when (start) {
            MediaStart.BUSY -> previous ?: MailExtras(ocrDone = false)

            MediaStart.FAILED_BEFORE -> (previous ?: MailExtras()).copy(ocrDone = true)

            MediaStart.READ -> try {
                readMedia(mail, previous, withOcr)
            } finally {
                withContext(NonCancellable) {
                    locked {
                        store.clearMediaAttempt(mail.accountUuid, mail.k9Id)
                        mediaRunning -= key
                    }
                }
            }
        }
    }

    private enum class MediaStart { READ, BUSY, FAILED_BEFORE }

    /** After a sync: a new inbox mail that counts as spam goes to the account's spam folder (if it has one). */
    private suspend fun moveIfSpam(accountUuid: String, k9Id: Long) {
        val mover = spamMover ?: return
        if (!settings.spam.enabled) return
        val stored = locked { store.get(accountUuid, k9Id) } ?: return
        if (stored.spam == null) return
        runCatching { mover.moveToSpam(accountUuid, stored.folderId, stored.uid) }
    }

    // ------------------------------------------------------------ check

    /** Features, context and the rules' verdict of one mail, ready for storing or for the model's second pass. */
    private inner class Evaluation(
        val mail: MailForCheck,
        val extras: MailExtras?,
        val features: JsonObject,
        val context: Map<String, JsonElement>,
        val config: JsonObject,
        val verdict: Verdict,
        /** Device contact or written to (spam rule 1). */
        val known: Boolean,
    ) {
        val sender: String = features["from"]?.let { from ->
            (from as? JsonObject)?.get("address")?.jsonPrimitive?.content
        }.orEmpty()
        val header: String = listOf("subject", "from").joinToString("\n") { name ->
            val value = mail.headers[name]?.firstOrNull().orEmpty()
            "${name.replaceFirstChar { it.uppercase() }}: $value"
        }
        val text: String = features["text"]?.jsonPrimitive?.content.orEmpty()

        fun record(verdict: Verdict = this.verdict) = StoredVerdict(
            accountUuid = mail.accountUuid,
            k9Id = mail.k9Id,
            folderId = mail.location.folderId,
            uid = mail.location.uid,
            headerMessageId = mail.headerMessageId,
            sender = sender,
            verdict = verdict,
            rulesVersion = RULES_VERSION,
            checkedAt = now(),
            extras = extras,
            spam = SpamRules.decide(
                sender = sender,
                headers = mail.headers,
                verdict = verdict,
                known = known,
                trusted = settings.trustedSenders.any { it.address == sender },
                settings = settings.spam,
            ),
        )

        /** The verdict with the model's facts added (`null` or unusable answer: unchanged), marked as asked. */
        suspend fun withAi(js: ProtectionEngine, answer: String?): StoredVerdict {
            val facts = answer?.let { js.aiFacts(it) }
            val rated = if (facts !=
                null
            ) {
                js.analyze(features, JsonObject(context + ("ai" to facts)), config)
            } else {
                verdict
            }
            val marked = JsonObject(rated.json + (AI_CHECKED to JsonPrimitive(true)))
            return record(Verdict.fromJson(marked))
        }
    }

    private fun config() = JsonObject(
        mapOf("trustedSenders" to JsonArray(settings.trustedSenders.map { JsonPrimitive(it.address) })),
    )

    private suspend fun evaluate(mail: MailForCheck, extras: MailExtras?): Evaluation {
        val js = engine()
        refreshContext(js)
        val config = config()
        val features = js.features(input(mail, extras), config)
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
        val from = features["from"]?.let { (it as? JsonObject)?.get("address")?.jsonPrimitive?.content }.orEmpty()
        val known = settings.spam.enabled && from.isNotEmpty() && js.knownSender(from)
        return Evaluation(mail, extras, features, context, config, verdict, known)
    }

    private suspend fun checkMail(mail: MailForCheck, extras: MailExtras?): StoredVerdict {
        val record = evaluate(mail, extras).record()
        save(record)
        return record
    }

    /**
     * QR codes in images and attachment types of [mail], and with [withOcr] the text in images (only the images the
     * add-on would read: linked images without text, or all when the mail is mostly images). [previous] is kept and
     * only completed. Runs outside the lock; each K-9 read takes it briefly.
     */
    private suspend fun readMedia(mail: MailForCheck, previous: MailExtras?, withOcr: Boolean): MailExtras {
        val js = engine()
        val scanner = media?.takeIf { settings.scanImages }
        var extras = previous ?: run {
            val images = MailMedia.images(mail)
            val qrUrls = if (scanner == null) {
                emptyList()
            } else {
                images.flatMap { part ->
                    partBytes(mail, part, MailMedia.MAX_IMAGE_BYTES)?.let { scanner.qrUrls(it) }.orEmpty()
                }
                    .distinct()
            }
            MailExtras(
                qrUrls = qrUrls,
                attachmentScan = scanAttachments(js, mail),
                ocrDone = scanner == null || images.isEmpty(),
            )
        }
        if (withOcr && scanner != null && !extras.ocrDone) {
            val candidates = js.ocrCandidates(js.features(input(mail, extras), config()))
            val imageTexts = LinkedHashMap<String, String>()
            MailMedia.images(mail)
                .filter { candidates.mostlyImages || MailMedia.imageKey(it) in candidates.linked }
                .take(MailMedia.MAX_OCR_IMAGES)
                .forEach { part ->
                    val key = MailMedia.imageKey(part)
                    if (key.isEmpty() || key in imageTexts) return@forEach
                    val text = partBytes(mail, part, MailMedia.MAX_IMAGE_BYTES)?.let { scanner.text(it) }
                    if (!text.isNullOrBlank()) imageTexts[key] = text
                }
            extras = extras.copy(imageTexts = imageTexts, ocrDone = true)
        }
        return extras
    }

    /** `attachmentScan` of the add-on: real file type and ZIP contents per file name. */
    private suspend fun scanAttachments(js: ProtectionEngine, mail: MailForCheck): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        for (part in MailMedia.attachments(mail)) {
            val name = part.name ?: continue
            if (name in result) continue
            val bytes = partBytes(mail, part, MailMedia.MAX_ATTACHMENT_BYTES) ?: continue
            val head = bytes.copyOf(minOf(bytes.size, MailMedia.HEAD_BYTES))
            val tailSize = minOf(bytes.size, MailMedia.ZIP_TAIL + MailMedia.ZIP_DIR_MAX)
            val tail = if (MailMedia.maybeZip(name)) bytes.copyOfRange(bytes.size - tailSize, bytes.size) else null
            result[name] = js.scanFile(name, head, tail, (bytes.size - tailSize).toLong())
        }
        return JsonObject(result)
    }

    private suspend fun partBytes(mail: MailForCheck, part: MailPart, maxBytes: Int): ByteArray? =
        if (part.partId == 0L) null else locked { texts.partBytes(mail.accountUuid, part.partId, maxBytes) }

    private fun save(record: StoredVerdict) {
        store.save(record)
        _revision.value++
    }

    /** Contacts and sender history in the engine, refreshed at most every [CONTEXT_MAX_AGE] ms. */
    private suspend fun refreshContext(js: ProtectionEngine) {
        val time = now()
        if (contextLoadedAt != Long.MIN_VALUE && time - contextLoadedAt < CONTEXT_MAX_AGE) return
        js.setContacts(contacts.contacts())
        js.setSenderIndex(SenderIndexBuilder(mailStore).build(time, settings.historyMonths, store.flaggedSenders()))
        contextLoadedAt = time
    }

    private fun input(mail: MailForCheck, extras: MailExtras?) = JsonObject(
        mapOf(
            "qrUrls" to JsonArray(extras?.qrUrls.orEmpty().map(::JsonPrimitive)),
            "imageTexts" to JsonObject(extras?.imageTexts.orEmpty().mapValues { JsonPrimitive(it.value) }),
            "attachmentScan" to (extras?.attachmentScan ?: JsonObject(emptyMap())),
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

    /** Inbox mails above the watermark with the time they reached the server (`internal_date`, else `date`). */
    private fun newInboxMails(accountUuid: String, watermark: Long): List<Pair<Long, Long>> =
        mailStore.open(accountUuid)?.use { db ->
            db.query(
                """
                SELECT m.id, coalesce(nullif(m.internal_date, 0), m.date, 0) FROM messages m
                JOIN folders f ON f.id = m.folder_id
                WHERE m.id > ? AND m.deleted = 0 AND m.empty = 0 AND m.uid IS NOT NULL AND lower(f.type) = 'inbox'
                ORDER BY m.id LIMIT $NEW_MAILS_PER_RUN
                """.trimIndent(),
                listOf(watermark.toString()),
            ).map { (it[0] as Long) to ((it[1] as Long?) ?: 0L) }
        }.orEmpty()

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { withContext(dispatcher) { block() } }

    companion object {
        /** Version of the rules (add-on core + this check); results of other versions are checked again on open. */
        const val RULES_VERSION = "addon-0.9.1+android-2"

        /** Key in the stored verdict JSON: the language model was asked (or was not needed) for this result. */
        private const val AI_CHECKED = "aiChecked"
        private const val AI_PAUSE_MILLIS = 5 * 60_000L
        private const val CONTEXT_MAX_AGE = 10 * 60_000L
        private const val MAX_REASONS = 8
        private const val MAX_REASON_CHARS = 160
        private const val THREAD_REFERENCES = 3
        private const val RELATED_DAYS = 180L
        private const val RELATED_CANDIDATES = 20
        private const val DAY_MILLIS = 86_400_000L
        private const val NEW_MAILS_PER_RUN = 200
        private const val ARRIVAL_SLACK_MILLIS = 24 * 60 * 60_000L
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
