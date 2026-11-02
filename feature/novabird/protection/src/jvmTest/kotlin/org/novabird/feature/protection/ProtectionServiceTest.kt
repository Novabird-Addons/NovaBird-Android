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
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
    }
    private val settings = InMemoryProtectionSettings()
    private var deviceContacts = emptyList<DeviceContact>()
    private val aiAnswers = mutableListOf<String>()
    private var aiAnswer: String? = null
    private val fakeAi = object : ProtectionAi {
        override fun isAvailable() = aiAnswer != null
        override suspend fun extract(header: String, text: String): String? {
            aiAnswers += header
            return aiAnswer
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
    ) {
        val address = from.substringAfter('<').substringBefore('>')
        val packedSender = "$address;\u0001${from.substringBefore(" <")}"
        k9.insert(id, folder, "u$id", "<m$id@example.com>", subject, NOW - id, packedSender)
        mails[id] = MailForCheck(
            accountUuid = account.uuid,
            k9Id = id,
            location = MessageLocation(account.uuid, folder, "u$id"),
            headerMessageId = "m$id@example.com",
            date = NOW - id,
            folderType = "inbox",
            headers = (mapOf("from" to from, "to" to "ich@meine-firma.example", "subject" to subject) + extraHeaders)
                .mapValues { listOf(it.value) },
            parts = listOf(MailPart("text/html", null, html)),
        )
    }

    private fun phishing(id: Long) = mail(
        id,
        "PayPal Service <service@paypal-konto.example.invalid>",
        "Ihr Konto",
        """<p>Konto gesperrt</p><a href="https://paypa1-login.example.invalid/x">www.paypal.de</a>""",
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
                    "uid TEXT, " +
                    "subject TEXT, date INTEGER, sender_list TEXT, to_list TEXT, cc_list TEXT, message_id TEXT, " +
                    "empty INTEGER default 0)",
            )
            listOf(INBOX to "inbox", SENT to "sent", TRASH to "trash").forEach { (id, type) ->
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
        ) = exec(
            "INSERT INTO messages (id, folder_id, uid, message_id, subject, date, sender_list, to_list, cc_list) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, '')",
            id, folder, uid, messageId, subject, date, sender, to,
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
        const val INBOX = 1L
        const val SENT = 2L
        const val TRASH = 3L
    }
}
