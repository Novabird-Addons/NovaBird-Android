package org.novabird.feature.search.internal.indexer

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.logging.testing.TestLogger
import org.novabird.feature.search.K9Account
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.SearchOptions
import org.novabird.feature.search.SearchResult
import org.novabird.feature.search.SearchSort
import org.novabird.feature.search.internal.NovaBirdSearchCore
import org.novabird.feature.search.internal.NovaBirdSearchService
import org.novabird.feature.search.internal.indexer.FakeK9MailStore.TestPart

class NovaBirdIndexerTest {
    private val account = K9Account(uuid = ACCOUNT, emails = listOf("leitung@firma.example"))
    private val mailStore = FakeK9MailStore(account)
    private val core = NovaBirdSearchCore(":memory:")
    private val testSubject = NovaBirdIndexer(core, mailStore, TestLogger(), batchSize = 2)
    private val search = NovaBirdSearchService(core, ownEmails = { account.emails }, now = { NOW })

    @AfterTest
    fun tearDown() = runBlocking<Unit> { core.close() }

    private fun fillMailbox() {
        mailStore.addFolder(1, "INBOX", "inbox")
        mailStore.addFolder(2, "Papierkorb", "trash")
        mailStore.addFolder(3, "Archiv", "archive")

        // quoted-printable, utf-8, with signature and quote that must not be indexed
        mailStore.insertMessage(
            id = 10, folderId = 1, uid = "u10", messageId = "<m10@firma.example>",
            subject = "Wochenspeisepläne KW 40", date = day(2026, 9, 20),
            sender = "jonas.mueller@firma.example;\u0001Jonas Müller", to = "leitung@firma.example",
            root = multipart(
                "multipart/mixed",
                text(
                    "text/plain",
                    "quoted-printable",
                    "utf-8",
                    "Hallo zusammen,=0Aanbei die Speisepl=C3=A4ne f=C3=BCr die n=C3=A4chsten Wochen.=0A=0A" +
                        "-- =0AJonas=0A",
                ),
                attachment("application/pdf", "attachment; filename*=utf-8''Wochenplan_K%C3%BCche.pdf"),
            ),
        )
        // base64 HTML only, windows-1252 declared as iso-8859-1 (euro sign 0x80)
        mailStore.insertMessage(
            id = 11, folderId = 1, uid = "u11", messageId = "<m11@extern.example>",
            subject = "Rechnung Oktober", date = day(2026, 10, 1),
            sender = "kanzlei@steuer.example;\u0001Steuerbüro", to = "leitung@firma.example",
            root = text(
                "text/html",
                "base64",
                "iso-8859-1",
                base64("<html><body><p>Ihre Rechnung &uuml;ber 120 \u0080 für die Wartung</p></body></html>"),
            ),
        )
        // from me, flagged, archived; body on disk
        mailStore.insertMessage(
            id = 12, folderId = 3, uid = "u12", messageId = "<m12@firma.example>",
            subject = "Re: Urlaubsplanung 2027", date = day(2026, 9, 25),
            sender = "leitung@firma.example;\u0001Firma Leitung",
            to = "petra.schneider@firma.example;\u0001Petra Schneider",
            root = TestPart(
                "text/plain",
                "Content-Type: text/plain; charset=utf-8\r\n" +
                    "References: <m5@firma.example>\r\n",
                body = "Danke Petra, ich trage mich ein.\n\n> Bitte tragt eure Urlaubswünsche ein".encodeToByteArray(),
                onDisk = true,
            ),
            flagged = true,
        )
        // in trash, without Message-ID
        mailStore.insertMessage(
            id = 13, folderId = 2, uid = "u13", messageId = null,
            subject = "Speiseplan alt", date = day(2026, 9, 1),
            sender = "jonas.mueller@firma.example;\u0001Jonas Müller", to = "leitung@firma.example",
            root = text("text/plain", null, "us-ascii", "Bitte ignorieren."),
        )
    }

    @Test
    fun `indexes K-9 messages and finds them with everyday language`() = runBlocking<Unit> {
        fillMailbox()

        val stats = testSubject.indexChanges()

        assertThat(stats.indexed).isEqualTo(4)
        assertThat(stats.total).isEqualTo(4)
        assertThat(ids(search("Speiseplan"))).containsExactly("m10@firma.example") // compound, trash excluded
        assertThat(ids(search("Speisepläne von Jonas mit PDF"))).containsExactly("m10@firma.example")
        assertThat(ids(search("Kueche"))).containsExactly("m10@firma.example") // attachment name, RFC 2231
        assertThat(ids(search("rechnung wartung"))).containsExactly("m11@extern.example") // base64 HTML
        assertThat(ids(search("naechsten Wochen"))).containsExactly("m10@firma.example") // umlauts folded
        assertThat(ids(search("von mir an Petra"))).containsExactly("m12@firma.example")
        assertThat(ids(search("markiert im Archiv"))).containsExactly("m12@firma.example")
        assertThat(ids(search("Urlaubswünsche"))).containsExactly() // quoted text is not indexed
        assertThat(ids(search("von Jonas"))).containsExactly("m10@firma.example")
        assertThat(ids(search("ignorieren im Papierkorb"))).containsExactly("k9-2-u13@novabird.invalid")
    }

    @Test
    fun `euro sign of windows-1252 text declared as iso-8859-1 is decoded like on the PC`() = runBlocking<Unit> {
        fillMailbox()
        testSubject.indexChanges()

        val raw = core.engine().evaluate(
            "NovaBird.db.select(\"SELECT count(*) FROM messages_fts WHERE messages_fts MATCH ?\", ['\"120\"'], ['n'])" +
                ".then((r) => String(r[0].n))",
        )

        assertThat(raw).isEqualTo("1")
        assertThat(MimeText.decodeBody(byteArrayOf(0x80.toByte()), null, "iso-8859-1")).isEqualTo("€")
    }

    @Test
    fun `best matches first, then the other hits by date`() = runBlocking<Unit> {
        mailStore.addFolder(1, "INBOX", "inbox")
        // the word in the subject (oldest mail) fits clearly better than at the end of a long text (newer mails)
        mailStore.insertMessage(
            id = 1, folderId = 1, uid = "u1", messageId = "<best@x.example>", subject = "Heizungswartung Angebot",
            date = day(2026, 9, 1), sender = "a@x.example", to = "leitung@firma.example",
            root = text("text/plain", null, "utf-8", "Anbei das Angebot für die Heizungswartung."),
        )
        for (i in 2..8) {
            mailStore.insertMessage(
                id = i.toLong(), folderId = 1, uid = "u$i", messageId = "<m$i@x.example>", subject = "Woche $i",
                date = day(2026, 9, i), sender = "b@x.example", to = "leitung@firma.example",
                root = text(
                    "text/plain",
                    null,
                    "utf-8",
                    "Termine, Listen und Pläne der Woche. " + FILLER.repeat(6) + " Angebot",
                ),
            )
        }
        testSubject.indexChanges()

        val result = search("Angebot", sort = SearchSort.BEST)

        assertThat(ids(result).first()).isEqualTo("best@x.example")
        assertThat(result.bestCount).isEqualTo(1)
        val rest = result.hits.drop(result.bestCount).map { it.date }
        assertThat(rest).isEqualTo(rest.sortedDescending())
        assertThat(ids(result).toSet().size).isEqualTo(8)
        // by date: no split
        assertThat(search("Angebot", sort = SearchSort.DATE).bestCount).isEqualTo(0)
    }

    @Test
    fun `hits carry the K-9 location for opening the message`() = runBlocking<Unit> {
        fillMailbox()
        testSubject.indexChanges()

        val hit = search("Rechnung").hits.single()

        assertThat(hit.location).isEqualTo(MessageLocation(ACCOUNT, folderId = 1, uid = "u11"))
        assertThat(hit.authorName).isEqualTo("Steuerbüro")
    }

    @Test
    fun `did you mean and chips`() = runBlocking<Unit> {
        fillMailbox()
        // suggest.js only proposes words that occur in at least two messages
        mailStore.insertMessage(
            id = 14, folderId = 1, uid = "u14", messageId = "<m14@extern.example>",
            subject = "Zweite Rechnung", date = day(2026, 10, 2),
            sender = "kanzlei@steuer.example", to = "leitung@firma.example",
            root = text("text/plain", null, "utf-8", "Anbei die Rechnung."),
        )
        testSubject.indexChanges()

        val typo = search("Rechnnung")
        val chips = search("Speiseplan von Jonas seit August mit PDF")
        val withoutDate = this@NovaBirdIndexerTest.search.searchWithoutChip(chips, "date", options)

        assertThat(typo.hits.size).isEqualTo(0)
        assertThat(typo.suggestion).isEqualTo("rechnung")
        assertThat(chips.chips.map { it.kind }).containsExactlyInAnyOrder("from", "date", "attachment", "text")
        assertThat(withoutDate.chips.map { it.kind }).containsExactlyInAnyOrder("from", "attachment", "text")
        assertThat(ids(withoutDate)).containsExactly("m10@firma.example")
    }

    @Test
    fun `removing a chip also removes its words from the text`() = runBlocking<Unit> {
        fillMailbox()
        testSubject.indexChanges()
        val text = "Speiseplan von Jonas seit August mit PDF"
        val chips = search(text)

        fun without(chipId: String) = runBlocking {
            this@NovaBirdIndexerTest.search.textWithoutChip(text, chips, chipId)
        }

        assertThat(without("date")).isEqualTo("Speiseplan von Jonas mit PDF")
        assertThat(without("from:0")).isEqualTo("Speiseplan seit August mit PDF")
        assertThat(without("attType:pdf")).isEqualTo("Speiseplan von Jonas seit August")
        assertThat(without("term:0")).isEqualTo("von Jonas seit August mit PDF")
    }

    @Test
    fun `second run only reads changed messages and removes deleted ones`() = runBlocking<Unit> {
        fillMailbox()
        testSubject.indexChanges()

        val unchanged = testSubject.indexChanges()
        mailStore.exec("UPDATE messages SET read = 1 WHERE id = 11")
        mailStore.exec("UPDATE messages SET deleted = 1 WHERE id = 13")
        mailStore.exec("UPDATE messages SET folder_id = 2 WHERE id = 12") // moved to trash
        val changed = testSubject.indexChanges()

        assertThat(unchanged.indexed).isEqualTo(0)
        assertThat(changed.indexed).isEqualTo(2)
        assertThat(changed.removed).isEqualTo(2)
        assertThat(changed.total).isEqualTo(3)
        assertThat(ids(search("gelesene Rechnung"))).containsExactly("m11@extern.example")
        assertThat(ids(search("ignorieren", includeTrash = true))).containsExactly()
        assertThat(search("Urlaubsplanung im Papierkorb").hits.single().location)
            .isEqualTo(MessageLocation(ACCOUNT, folderId = 2, uid = "u12"))
    }

    @Test
    fun `search on a few thousand messages is fast enough`() = runBlocking<Unit> {
        mailStore.addFolder(1, "INBOX", "inbox")
        val words = listOf("Speiseplan", "Rechnung", "Wartung", "Urlaub", "Stundenplan", "Bericht", "Termin", "Angebot")
        for (i in 0 until MESSAGE_COUNT) {
            mailStore.insertMessage(
                id = 1000L + i, folderId = 1, uid = "u$i", messageId = "<p$i@test.example>",
                subject = "${words[i % words.size]} Nummer $i", date = day(2026, 1 + i % 9, 1 + i % 28),
                sender = "person${i % 50}@test.example;\u0001Person ${i % 50}", to = "leitung@firma.example",
                root = text("text/plain", null, "utf-8", "Text $i über ${words[(i * 7) % words.size]} und mehr."),
            )
        }

        val indexTime = measureTime { testSubject.indexChanges() }
        val queries = listOf(
            "Speiseplan",
            "Rechnungen von Person 7",
            "Wartung seit März mit Anhang",
            "Urlaub oder Termin",
            "Bericht im Juni",
            "Rechnnung",
            "ueber Angebot",
            "",
        )
        search("Speiseplan") // warm up
        val times = queries.map { q -> q to measureTime { search(q, sort = SearchSort.RELEVANCE) } }

        println("Indexieren von $MESSAGE_COUNT Mails: $indexTime")
        times.forEach { (q, t) -> println("Suche \"$q\": $t") }
        times.forEach { (_, t) -> assertThat(t.inWholeMilliseconds).isLessThan(MAX_SEARCH_MS) }
        assertThat(search("Speiseplan").hits.isNotEmpty()).isTrue()
        assertThat(search("Rechnung von Person 7").hits.firstOrNull()).isNotNull()
    }

    private val options = SearchOptions(sort = SearchSort.DATE)

    private suspend fun search(
        text: String,
        includeTrash: Boolean = false,
        sort: SearchSort = SearchSort.DATE,
    ): SearchResult = search.search(text, options.copy(includeTrash = includeTrash, sort = sort))

    private fun ids(result: SearchResult) = result.hits.map { it.headerMessageId }

    private companion object {
        const val ACCOUNT = "3c1a5f7e-0000-4000-8000-00000000a001"
        const val MESSAGE_COUNT = 3000
        const val MAX_SEARCH_MS = 500L
        const val FILLER = "Dazu kommen Hinweise zu Räumen, Zeiten, Wegen, Fahrten, Essen, Getränken und Pausen."

        // Monday, 05.10.2026, 12:00 Europe/Berlin (like the NovaBird-Search fixtures)
        val NOW = java.time.ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, java.time.ZoneId.of("Europe/Berlin"))
            .toInstant().toEpochMilli()

        fun day(year: Int, month: Int, day: Int) =
            java.time.ZonedDateTime.of(year, month, day, 9, 0, 0, 0, java.time.ZoneId.of("Europe/Berlin"))
                .toInstant().toEpochMilli()

        fun text(mimeType: String, encoding: String?, charset: String?, body: String) = TestPart(
            mimeType = mimeType,
            header = "Content-Type: $mimeType; charset=$charset\r\n" +
                (encoding?.let { "Content-Transfer-Encoding: $it\r\n" } ?: ""),
            body = body.toByteArray(Charsets.ISO_8859_1).takeIf { encoding != null || charset == "us-ascii" }
                ?: body.encodeToByteArray(),
            encoding = encoding,
        )

        fun base64(text: String): String =
            java.util.Base64.getMimeEncoder().encodeToString(text.toByteArray(Charsets.ISO_8859_1))

        fun attachment(mimeType: String, disposition: String) = TestPart(
            mimeType = mimeType,
            header = "Content-Type: $mimeType\r\nContent-Disposition: $disposition\r\n",
            body = null,
        )

        fun multipart(mimeType: String, vararg children: TestPart) = TestPart(
            mimeType = mimeType,
            header = "Content-Type: $mimeType; boundary=\"b\"\r\n",
            children = children.toList(),
        )
    }
}
