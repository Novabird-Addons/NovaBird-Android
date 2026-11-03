package org.novabird.feature.protection

import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.novabird.feature.search.K9Account
import org.novabird.feature.search.K9MailDatabase
import org.novabird.feature.search.K9MailStore
import org.novabird.feature.search.MailForAi
import org.novabird.feature.search.MailForCheck
import org.novabird.feature.search.MailPart
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.NovaBirdMailTexts
import org.novabird.feature.search.RecentMails

class ProtectionServiceTest {
    private val account = K9Account("acc-1", listOf("ich@meine-firma.example"))
    private val k9 = FakeK9(account)
    private val mails = mutableMapOf<Long, MailForCheck>()
    private val texts = object : NovaBirdMailTexts {
        override suspend fun mail(location: MessageLocation): MailForAi? = null
        override suspend fun recentMails(hours: Int, unreadOnly: Boolean, max: Int) = RecentMails(emptyList(), 0)
        override suspend fun mailForCheck(accountUuid: String, k9Id: Long) = mails[k9Id]
        override suspend fun partBytes(accountUuid: String, partId: Long, maxBytes: Int) =
            partData[partId]?.takeIf { it.size <= maxBytes }
    }
    private val partData = mutableMapOf<Long, ByteArray>()
    private var qrAnswer = emptyList<String>()
    private var ocrAnswer: String? = null
    private val ocrCalls = mutableListOf<Int>()
    private val fakeMedia = object : MediaScanner {
        override suspend fun qrUrls(image: ByteArray) = qrAnswer
        override suspend fun text(image: ByteArray): String? {
            ocrCalls += image.size
            return ocrAnswer
        }
    }
    private val settings = InMemoryProtectionSettings()
    private var deviceContacts = emptyList<DeviceContact>()
    private val aiAnswers = mutableListOf<String>()
    private var aiAnswer: String? = null
    private var aiFails = false
    private val fakeAi = object : ProtectionAi {
        override fun isAvailable() = aiAnswer != null
        override suspend fun extract(header: String, text: String): String? {
            aiAnswers += header
            if (aiFails) throw IllegalStateException("Helper nicht erreichbar")
            return aiAnswer
        }
    }
    private val moves = mutableListOf<String>()
    private val fakeMover = object : SpamMover {
        override suspend fun moveToSpam(accountUuid: String, folderId: Long, uid: String): Boolean {
            moves += "spam:$folderId:$uid"
            return true
        }

        override suspend fun moveToInbox(accountUuid: String, folderId: Long, uid: String): Boolean {
            moves += "inbox:$folderId:$uid"
            return true
        }
    }
    private val store = ProtectionStore(":memory:")
    private val engine = lazy {
        runBlocking { ProtectionEngine.create(ProtectionScripts.load(), Dispatchers.Default.limitedParallelism(1)) }
    }
    private val service = ProtectionService(
        engine = { engine.value },
        texts = texts,
        mailStore = k9,
        store = store,
        settings = settings,
        contacts = { deviceContacts },
        dispatcher = Dispatchers.Default.limitedParallelism(1),
        now = { NOW },
        ai = fakeAi,
        media = fakeMedia,
        spamMover = fakeMover,
    )

    @AfterTest
    fun tearDown() {
        if (engine.isInitialized()) engine.value.close()
        store.close()
    }

    private fun mail(
        id: Long,
        from: String,
        subject: String,
        html: String = "<p>Hallo</p>",
        folder: Long = INBOX,
        extraHeaders: Map<String, String> = emptyMap(),
        extraParts: List<MailPart> = emptyList(),
        arrived: Long = NOW - id,
    ) {
        val address = from.substringAfter('<').substringBefore('>')
        val packedSender = "$address;\u0001${from.substringBefore(" <")}"
        k9.insert(id, folder, "u$id", "<m$id@example.com>", subject, NOW - id, packedSender, arrived = arrived)
        mails[id] = MailForCheck(
            accountUuid = account.uuid,
            k9Id = id,
            location = MessageLocation(account.uuid, folder, "u$id"),
            headerMessageId = "m$id@example.com",
            date = NOW - id,
            folderType = if (folder == SPAM) "spam" else "inbox",
            headers = (mapOf("from" to from, "to" to "ich@meine-firma.example", "subject" to subject) + extraHeaders)
                .mapValues { listOf(it.value) },
            parts = listOf(MailPart("text/html", null, html)) + extraParts,
        )
    }

    /** A locally stored part with [bytes] (image or attachment). */
    private fun part(id: Long, type: String, name: String?, bytes: ByteArray, contentId: String? = null): MailPart {
        partData[id] = bytes
        return MailPart(type, name, null, id, contentId, bytes.size.toLong(), downloaded = true)
    }

    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("inhalt".encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val image = ByteArray(5_000) { 7 }

    private fun phishing(id: Long, arrived: Long = NOW - id) = mail(
        id,
        "PayPal Service <service@paypal-konto.example.invalid>",
        "Ihr Konto",
        """<p>Konto gesperrt</p><a href="https://paypa1-login.example.invalid/x">www.paypal.de</a>""",
        arrived = arrived,
    )

    @Test
    fun `nothing is checked while the protection is off`() = runBlocking<Unit> {
        phishing(1)

        assertThat(service.checkNewMails()).isEqualTo(0)
        assertThat(service.verdictOnOpen(account.uuid, 1)).isNull()
    }

    @Test
    fun `after switching on only new inbox mails are checked in the background, older ones when opened`() =
        runBlocking<Unit> {
            phishing(1)
            service.enable(consentVersion = 1)
            phishing(2)
            phishing(3)
            mail(4, "Shop <info@shop.example>", "Werbung", folder = TRASH)

            assertThat(service.checkNewMails()).isEqualTo(2)
            assertThat(store.get(account.uuid, 1)).isNull()
            assertThat(store.get(account.uuid, 2)?.level).isEqualTo(ProtectionLevel.DANGEROUS)
            assertThat(store.get(account.uuid, 4)).isNull()
            assertThat(service.checkNewMails()).isEqualTo(0)

            val opened = service.verdictOnOpen(account.uuid, 1)
            assertThat(opened?.level).isEqualTo(ProtectionLevel.DANGEROUS)
            assertThat(service.levels(account.uuid, listOf(1, 2, 3, 4)).keys).isEqualTo(setOf(1L, 2L, 3L))
        }

    @Test
    fun `older mails loaded later are not checked in the background, only mails that arrived since the last run`() =
        runBlocking<Unit> {
            service.enable(consentVersion = 1)
            // "all messages": a year of old mail arrives on the phone with new local ids
            phishing(1, arrived = NOW - 200 * DAY)
            phishing(2, arrived = NOW - 3 * DAY)
            phishing(3)

            assertThat(service.checkNewMails()).isEqualTo(1)
            assertThat(store.get(account.uuid, 1)).isNull()
            assertThat(store.get(account.uuid, 2)).isNull()
            assertThat(store.get(account.uuid, 3)?.level).isEqualTo(ProtectionLevel.DANGEROUS)
            assertThat(store.watermark(account.uuid)).isEqualTo(3L)
            assertThat(service.verdictOnOpen(account.uuid, 1)?.level).isEqualTo(ProtectionLevel.DANGEROUS)
        }

    @Test
    fun `data from before the arrival time was stored does not start a backlog`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        // as stored by the version before: watermark only
        store.setWatermark(account.uuid, 0, since = null)
        phishing(1, arrived = NOW - 30 * DAY)
        phishing(2)

        assertThat(service.checkNewMails()).isEqualTo(1)
        assertThat(store.get(account.uuid, 1)).isNull()
        assertThat(store.since(account.uuid)).isEqualTo(NOW)
    }

    @Test
    fun `a mail whose images could not be read before is checked without them`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        ocrAnswer = "PayPal Konto bestätigen"
        qrAnswer = listOf("https://qr-ziel.example.invalid/login")
        val html = """<p>Hallo</p><a href="https://konto-check.example.invalid/x"><img src="cid:logo7@x"></a>"""
        for (id in 1L..2L) {
            val logo = part(200 + id, "image/png", "logo.png", image, "logo7@x")
            mail(id, "Service <info@service.example>", "Konto", html, extraParts = listOf(logo))
        }
        // the app died while reading the images of mail 1
        store.markMediaAttempt(account.uuid, 1)

        assertThat(service.checkNewMails()).isEqualTo(2)
        val skipped = store.get(account.uuid, 1)!!
        assertThat(skipped.extras?.ocrDone).isEqualTo(true)
        assertThat(skipped.extras?.qrUrls).isEqualTo(emptyList())
        assertThat(skipped.extras?.imageTexts).isEqualTo(emptyMap())
        assertThat(store.get(account.uuid, 2)!!.extras?.imageTexts)
            .isEqualTo(mapOf("logo7" to "PayPal Konto bestätigen"))
        assertThat(ocrCalls.size).isEqualTo(1)
        assertThat(store.mediaAttempted(account.uuid, 1)).isEqualTo(true)
        assertThat(store.mediaAttempted(account.uuid, 2)).isEqualTo(false)
    }

    @Test
    fun `an Authentication-Results header written by the sender is ignored once the own server is known`() =
        runBlocking<Unit> {
            service.enable(consentVersion = 1)
            fun withAuth(id: Long, vararg values: String) {
                mail(id, "Kollege <k@partner.example>", "Hallo $id")
                mails[id] =
                    mails.getValue(id).let {
                        it.copy(
                            headers =
                            it.headers + ("authentication-results" to values.toList()),
                        )
                    }
            }
            // the own server writes "mx.provider.example" on top of every mail
            for (id in 1L..10L) withAuth(id, "mx.provider.example; dmarc=pass header.from=partner.example")
            service.checkNewMails()

            // forged pass on top, the server's fail below: the server's result counts
            withAuth(11, "evil.example; dmarc=pass", "mx.provider.example; dmarc=fail header.from=partner.example")
            service.checkNewMails()
            assertThat(store.get(account.uuid, 11)!!.verdict.signals.map { it.id }).contains("dmarcFail")
        }

    @Test
    fun `the learned mail server ids stay bounded and a new provider wins`() {
        // a sender can make up a new id per mail: at most 10 are kept, and from 200 mails on the counts are halved
        for (i in 0 until 500) {
            val id = when {
                i < 300 -> "old.example"
                i % 3 != 0 -> "new.example"
                else -> "x$i.example"
            }
            store.countAuthserv(account.uuid, id)
        }
        val counts = store.authservCounts(account.uuid)
        assertThat(counts.size).isLessThanOrEqualTo(10)
        assertThat(counts.values.sum()).isLessThan(200L)
        assertThat(counts.getValue("new.example")).isGreaterThan(counts.getValue("old.example"))
    }

    @Test
    fun `without own Authentication-Results headers, ones in the mail are not believed`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        for (id in 1L..10L) mail(id, "Kollege <k@partner.example>", "Hallo $id")
        service.checkNewMails()

        mail(
            11,
            "Kollege <k@partner.example>",
            "Hallo",
            extraHeaders = mapOf("authentication-results" to "x.example; dmarc=fail"),
        )
        service.checkNewMails()
        assertThat(store.get(account.uuid, 11)!!.verdict.signals.map { it.id }).doesNotContain("dmarcFail")
    }

    @Test
    fun `a device contact is known, a stranger is a first contact`() = runBlocking<Unit> {
        deviceContacts = listOf(DeviceContact(listOf("Anna Beispiel"), listOf("anna@partner.example")))
        service.enable(consentVersion = 1)
        mail(1, "Anna Beispiel <anna@partner.example>", "Termin")
        mail(2, "Bert <bert@fremd.example>", "Termin")

        val known = service.verdictOnOpen(account.uuid, 1)!!
        val stranger = service.verdictOnOpen(account.uuid, 2)!!

        assertThat(known.verdict.signals.map { it.id }).doesNotContain("firstContact")
        assertThat(stranger.verdict.signals.map { it.id }).contains("firstContact")
    }

    @Test
    fun `a reply to a mail that is not in the mailbox is noticed, a real reply is not`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        k9.insert(50, SENT, "s50", "<eigene@meine-firma.example>", "Angebot", NOW - 1000, "ich@meine-firma.example")
        val customer = "Kunde <kunde@kunde.example>"
        mail(1, customer, "Re: Angebot", extraHeaders = mapOf("in-reply-to" to "<eigene@meine-firma.example>"))
        mail(2, customer, "Re: Angebot", extraHeaders = mapOf("in-reply-to" to "<fremd@irgendwo.example>"))

        val real = service.verdictOnOpen(account.uuid, 1)!!
        val fake = service.verdictOnOpen(account.uuid, 2)!!

        assertThat(real.verdict.signals.map { it.id }).doesNotContain("replyThreadUnknown")
        assertThat(fake.verdict.signals.map { it.id }).contains("replyThreadUnknown")
    }

    @Test
    fun `someone the user wrote to counts as known`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        k9.insert(60, SENT, "s60", "<a@x>", "Hallo", NOW - 5000, "ich@meine-firma.example", to = "neu@kunde.example")
        mail(1, "Neuer Kunde <neu@kunde.example>", "Frage")

        val verdict = service.verdictOnOpen(account.uuid, 1)!!

        assertThat(verdict.verdict.signals.map { it.id }).doesNotContain("firstContact")
        assertThat(verdict.sender).isEqualTo("neu@kunde.example")
        assertThat(store.get(account.uuid, 1)).isNotNull()
    }

    @Test
    fun `trusting is refused for a dangerous mail and allowed for a suspicious one, undo brings the warning back`() =
        runBlocking<Unit> {
            service.enable(consentVersion = 1)
            phishing(1)
            // the add-on's own test case for "bank name from a foreign domain": suspicious, not dangerous
            mail(2, "Kreissparkasse Waiblingen <info@kundenservice-check.de>", "Info", "<p>Hallo</p>")

            assertThat(service.verdictOnOpen(account.uuid, 1)?.level).isEqualTo(ProtectionLevel.DANGEROUS)
            assertThat(service.trustSenderOf(account.uuid, 1, "de")).isEqualTo(TrustOutcome.REFUSED_DANGEROUS)
            assertThat(settings.trustedSenders).isEqualTo(emptyList())

            val before = service.verdictOnOpen(account.uuid, 2)!!
            assertThat(before.level).isEqualTo(ProtectionLevel.SUSPICIOUS)
            assertThat(service.trustSenderOf(account.uuid, 2, "de")).isEqualTo(TrustOutcome.TRUSTED)
            val trusted = settings.trustedSenders.single()
            assertThat(trusted.address).isEqualTo("info@kundenservice-check.de")
            assertThat(trusted.via).isEqualTo(TrustedVia.BANNER)
            assertThat(trusted.reasons.isNotEmpty()).isEqualTo(true)
            // the stored result is gone and the next open rates the mail again, now as normal
            assertThat(store.get(account.uuid, 2)).isNull()
            assertThat(service.verdictOnOpen(account.uuid, 2)?.level).isEqualTo(ProtectionLevel.NORMAL)

            service.untrust(trusted.address)
            assertThat(settings.trustedSenders).isEqualTo(emptyList())
            assertThat(service.verdictOnOpen(account.uuid, 2)?.level).isEqualTo(ProtectionLevel.SUSPICIOUS)
        }

    @Test
    fun `the model is asked in the grey zone only and can only add signals`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        aiAnswer = """{"ok":true,"claimedOrg":"Sparkasse","claimedDomain":null,"asksFor":"login","urgency":true,""" +
            """"injection":false,"model":"test"}"""
        mail(1, "Kundenservice <info@fremd.example>", "Ihr Zugang", "<p>Bitte melden Sie sich heute noch an.</p>")
        phishing(2)

        assertThat(service.checkNewMails()).isEqualTo(2)

        // grey zone: the model's claim of a bank that does not match the domain is a new signal
        val grey = store.get(account.uuid, 1)!!
        assertThat(grey.verdict.signals.map { it.id }).contains("aiClaimMismatch")
        // dangerous mails are never sent to the model; only the grey one was asked
        assertThat(aiAnswers.size).isEqualTo(1)
        assertThat(aiAnswers.single()).contains("Ihr Zugang")

        // a soothing answer cannot lower a verdict: a second pass with "nothing claimed" keeps the stored signals
        aiAnswers.clear()
        service.refineWithAi(account.uuid, 1)
        assertThat(aiAnswers.size).isEqualTo(0)
        assertThat(store.get(account.uuid, 1)!!.verdict.signals.map { it.id }).contains("aiClaimMismatch")
    }

    @Test
    fun `on open the rules answer first, the model follows and the opened mail is rated again`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        aiAnswer = """{"ok":true,"claimedOrg":"Sparkasse","claimedDomain":null,"asksFor":"login","urgency":true,""" +
            """"injection":false,"model":"test"}"""
        mail(1, "Kundenservice <info@fremd.example>", "Ihr Zugang", "<p>Bitte melden Sie sich heute noch an.</p>")

        val first = service.verdictOnOpen(account.uuid, 1)!!
        assertThat(first.verdict.signals.map { it.id }).doesNotContain("aiClaimMismatch")
        assertThat(aiAnswers.size).isEqualTo(0)

        service.refineWithAi(account.uuid, 1)
        val second = service.storedVerdict(account.uuid, 1)!!
        assertThat(second.verdict.signals.map { it.id }).contains("aiClaimMismatch")
        assertThat(second.verdict.score > first.verdict.score).isEqualTo(true)
    }

    @Test
    fun `an unreachable model pauses the questions and the mail is asked again later`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        aiAnswer = """{"ok":true,"claimedOrg":"Sparkasse","claimedDomain":null,"asksFor":"login","urgency":true,""" +
            """"injection":false,"model":"test"}"""
        aiFails = true
        mail(1, "Kundenservice <info@fremd.example>", "Ihr Zugang", "<p>Bitte melden Sie sich heute noch an.</p>")
        mail(2, "Service <info@andere.example>", "Noch ein Zugang", "<p>Bitte melden Sie sich an.</p>")

        service.checkNewMails()

        // the first call failed, the second mail was not even tried; nothing is marked as asked
        assertThat(aiAnswers.size).isEqualTo(1)
        assertThat(store.get(account.uuid, 1)!!.verdict.signals.map { it.id }).doesNotContain("aiClaimMismatch")
        aiFails = false
        service.refineWithAi(account.uuid, 1)
        assertThat(aiAnswers.size).isEqualTo(1) // still paused
    }

    @Test
    fun `a QR code in an image that leads somewhere else is a signal`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        qrAnswer = listOf("https://paket-zustellung.example.invalid/track")
        mail(
            1,
            "Versand <info@versand.example>",
            "Ihre Sendung",
            "<p>Bitte scannen</p><img src=\"cid:qr1@x\">",
            extraParts = listOf(part(101, "image/png", "qr.png", image, "qr1@x")),
        )

        val verdict = service.verdictOnOpen(account.uuid, 1)!!

        assertThat(verdict.verdict.signals.map { it.id }).contains("qrForeignLink")
        assertThat(verdict.extras?.qrUrls).isEqualTo(qrAnswer)
    }

    @Test
    fun `text in a linked image is read after the first answer on open, and right away after a sync`() =
        runBlocking<Unit> {
            service.enable(consentVersion = 1)
            ocrAnswer = "PayPal Konto bestätigen"
            val html = """<p>Hallo</p><a href="https://konto-check.example.invalid/x"><img src="cid:logo7@x"></a>"""
            mail(
                1,
                "Service <info@service.example>",
                "Konto",
                html,
                extraParts = listOf(part(201, "image/png", "logo.png", image, "logo7@x")),
            )

            val first = service.verdictOnOpen(account.uuid, 1)!!
            assertThat(first.verdict.signals.map { it.id }).doesNotContain("imageBrandMismatch")
            assertThat(first.extras?.ocrDone).isEqualTo(false)
            assertThat(ocrCalls.size).isEqualTo(0)

            service.refineWithImages(account.uuid, 1)
            val second = store.get(account.uuid, 1)!!
            assertThat(second.verdict.signals.map { it.id }).contains("imageBrandMismatch")
            assertThat(second.extras?.imageTexts).isEqualTo(mapOf("logo7" to "PayPal Konto bestätigen"))
            assertThat(ocrCalls.size).isEqualTo(1)

            // after a sync the text is read before the result is stored
            mail(
                2,
                "Service <info@service.example>",
                "Konto",
                html,
                extraParts = listOf(part(202, "image/png", "logo.png", image, "logo7@x")),
            )
            service.checkNewMails()
            assertThat(store.get(account.uuid, 2)!!.verdict.signals.map { it.id }).contains("imageBrandMismatch")
        }

    @Test
    fun `images are not read when switched off`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        settings.scanImages = false
        qrAnswer = listOf("https://paket-zustellung.example.invalid/track")
        mail(
            1,
            "Versand <info@versand.example>",
            "Sendung",
            extraParts = listOf(part(301, "image/png", "qr.png", image)),
        )

        val verdict = service.verdictOnOpen(account.uuid, 1)!!

        assertThat(verdict.verdict.signals.map { it.id }).doesNotContain("qrForeignLink")
        assertThat(verdict.extras?.ocrDone).isEqualTo(true)
    }

    @Test
    fun `an archive with a program inside and a program named as PDF are found`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        mail(
            1,
            "Buchhaltung <rechnung@lieferant.example>",
            "Rechnung",
            extraParts = listOf(part(401, "application/zip", "rechnung.zip", zip("docs/", "docs/rechnung.exe"))),
        )
        val program = byteArrayOf(0x4d, 0x5a) + ByteArray(2_000)
        mail(
            2,
            "Buchhaltung <rechnung@lieferant.example>",
            "Rechnung",
            extraParts = listOf(part(402, "application/pdf", "rechnung.pdf", program)),
        )

        val archive = service.verdictOnOpen(account.uuid, 1)!!
        val disguised = service.verdictOnOpen(account.uuid, 2)!!

        assertThat(archive.verdict.signals.map { it.id }).contains("archiveRisky")
        assertThat(disguised.verdict.signals.map { it.id }).contains("disguisedFile")
        assertThat(disguised.level).isEqualTo(ProtectionLevel.DANGEROUS)
    }

    @Test
    fun `spam from a blocked domain is moved after a sync, not when a mail is opened`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        settings.spam = SpamSettings(enabled = true, blocked = listOf("werbung.example"))
        mail(1, "Angebote <news@werbung.example>", "Nur heute")
        mail(2, "Kollegin <anna@partner.example>", "Termin")

        service.checkNewMails()

        assertThat(store.get(account.uuid, 1)?.spam).isEqualTo(SpamDecision(SpamReason.BLOCKED, "werbung.example"))
        assertThat(store.get(account.uuid, 2)?.spam).isNull()
        assertThat(moves).isEqualTo(listOf("spam:$INBOX:u1"))
        assertThat(service.marks(account.uuid, listOf(1, 2)).mapValues { it.value.spam })
            .isEqualTo(mapOf(1L to true, 2L to false))

        mail(3, "Angebote <news@werbung.example>", "Alt")
        store.clearWatermarks()
        assertThat(service.verdictOnOpen(account.uuid, 3)?.spam?.reason).isEqualTo(SpamReason.BLOCKED)
        assertThat(moves.size).isEqualTo(1)
    }

    @Test
    fun `a contact is never spam, and nothing happens while spam detection is off`() = runBlocking<Unit> {
        deviceContacts = listOf(DeviceContact(listOf("Anna"), listOf("anna@partner.example")))
        service.enable(consentVersion = 1)
        val flagged = mapOf("x-spam-flag" to "YES")
        mail(1, "Anna <anna@partner.example>", "Hallo", extraHeaders = flagged)
        mail(2, "Fremd <x@fremd.example>", "Hallo", extraHeaders = flagged)

        service.checkNewMails()
        assertThat(store.get(account.uuid, 2)?.spam).isNull()
        assertThat(moves).isEqualTo(emptyList())

        settings.spam = SpamSettings(enabled = true)
        mail(3, "Anna <anna@partner.example>", "Hallo", extraHeaders = flagged)
        mail(4, "Fremd <x@fremd.example>", "Hallo", extraHeaders = flagged)
        service.checkNewMails()
        assertThat(store.get(account.uuid, 3)?.spam).isNull()
        assertThat(store.get(account.uuid, 4)?.spam?.reason).isEqualTo(SpamReason.SERVER)
        assertThat(moves).isEqualTo(listOf("spam:$INBOX:u4"))
    }

    @Test
    fun `not spam allows the sender and moves a mail from the spam folder back`() = runBlocking<Unit> {
        service.enable(consentVersion = 1)
        settings.spam = SpamSettings(enabled = true, blocked = listOf("news@werbung.example"))
        mail(1, "Angebote <news@werbung.example>", "Nur heute", folder = SPAM)

        assertThat(service.verdictOnOpen(account.uuid, 1)?.spam?.reason).isEqualTo(SpamReason.BLOCKED)
        assertThat(service.notSpam(account.uuid, 1)).isEqualTo(true)

        assertThat(settings.spam.allowed).isEqualTo(listOf("news@werbung.example"))
        assertThat(settings.spam.blocked).isEqualTo(emptyList())
        assertThat(moves).isEqualTo(listOf("inbox:$SPAM:u1"))
        assertThat(service.verdictOnOpen(account.uuid, 1)?.spam).isNull()
    }

    @Test
    fun `extras survive a round trip through JSON`() {
        val extras = MailExtras(listOf("https://a.example/"), mapOf("logo" to "Text"), ocrDone = false)

        assertThat(MailExtras.fromJson(extras.toJson().toString())).isEqualTo(extras)
        assertThat(MailExtras.fromJson("kaputt")).isNull()
    }

    @Test
    fun `trusted senders survive a round trip through JSON`() {
        val list = listOf(
            TrustedSender("a@x.example", 1000L, TrustedVia.BANNER, 45, ProtectionLevel.SUSPICIOUS, listOf("Grund 1")),
            TrustedSender("b@y.example", 2000L, TrustedVia.SETTINGS),
        )

        assertThat(TrustedSender.listFromJson(TrustedSender.listToJson(list))).isEqualTo(list)
        assertThat(TrustedSender.listFromJson("kaputt")).isEqualTo(emptyList())
    }

    /** K-9 database for tests: folders and messages with the columns the protection reads, in memory. */
    private class FakeK9(private val account: K9Account) : K9MailStore {
        private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

        init {
            connection.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY, name TEXT, type TEXT)")
            connection.execSQL(
                "CREATE TABLE messages (id INTEGER PRIMARY KEY, deleted INTEGER default 0, folder_id INTEGER, " +
                    "uid TEXT, subject TEXT, date INTEGER, internal_date INTEGER, " +
                    "sender_list TEXT, to_list TEXT, cc_list TEXT, message_id TEXT, empty INTEGER default 0)",
            )
            listOf(INBOX to "inbox", SENT to "sent", TRASH to "trash", SPAM to "spam").forEach { (id, type) ->
                exec("INSERT INTO folders (id, name, type) VALUES (?, ?, ?)", id, type, type)
            }
        }

        @Suppress("LongParameterList")
        fun insert(
            id: Long,
            folder: Long,
            uid: String,
            messageId: String,
            subject: String,
            date: Long,
            sender: String,
            to: String = "ich@meine-firma.example",
            arrived: Long = date,
        ) = exec(
            "INSERT INTO messages (id, folder_id, uid, message_id, subject, date, internal_date, " +
                "sender_list, to_list, cc_list) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '')",
            id, folder, uid, messageId, subject, date, arrived, sender, to,
        )

        private fun exec(sql: String, vararg args: Any?) {
            connection.prepare(sql).use { st ->
                args.forEachIndexed { i, v ->
                    when (v) {
                        is Long -> st.bindLong(i + 1, v)
                        else -> st.bindText(i + 1, v.toString())
                    }
                }
                st.step()
            }
        }

        override fun accounts() = listOf(account)

        override fun open(accountUuid: String): K9MailDatabase? = object : K9MailDatabase {
            override fun query(sql: String, args: List<String>): List<List<Any?>> = connection.prepare(sql).use { st ->
                args.forEachIndexed { i, v -> st.bindText(i + 1, v) }
                buildList {
                    while (st.step()) {
                        add(
                            List(st.getColumnCount()) { c ->
                                when (st.getColumnType(c)) {
                                    SQLITE_DATA_INTEGER -> st.getLong(c)
                                    SQLITE_DATA_FLOAT -> st.getDouble(c)
                                    SQLITE_DATA_TEXT -> st.getText(c)
                                    else -> null
                                }
                            },
                        )
                    }
                }
            }

            override fun readPartFile(partId: Long): ByteArray? = null

            override fun close() = Unit
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24 * 60 * 60_000L
        const val INBOX = 1L
        const val SENT = 2L
        const val TRASH = 3L
        const val SPAM = 4L
    }
}
