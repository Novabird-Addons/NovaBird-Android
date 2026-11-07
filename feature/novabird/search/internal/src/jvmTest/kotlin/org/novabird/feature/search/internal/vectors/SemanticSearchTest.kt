package org.novabird.feature.search.internal.vectors

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.thunderbird.core.logging.testing.TestLogger
import org.novabird.feature.search.K9Account
import org.novabird.feature.search.NovaBirdVectorBackend
import org.novabird.feature.search.SearchOptions
import org.novabird.feature.search.SearchResult
import org.novabird.feature.search.SearchSort
import org.novabird.feature.search.VectorItem
import org.novabird.feature.search.internal.NovaBirdSearchCore
import org.novabird.feature.search.internal.NovaBirdSearchService
import org.novabird.feature.search.internal.indexer.FakeK9MailStore
import org.novabird.feature.search.internal.indexer.FakeK9MailStore.TestPart
import org.novabird.feature.search.internal.indexer.NovaBirdIndexer

class SemanticSearchTest {
    private val account = K9Account(uuid = ACCOUNT, emails = listOf("leitung@firma.example"))
    private val mailStore = FakeK9MailStore(account)
    private val core = NovaBirdSearchCore(":memory:")
    private val semanticIndex = SemanticIndex(core, { TopicBackend }, TestLogger())
    private val indexer = NovaBirdIndexer(core, mailStore, TestLogger(), semanticIndex)
    private val search = NovaBirdSearchService(core, ownEmails = { account.emails }, now = { NOW }, semanticIndex)
    private val options = SearchOptions(sort = SearchSort.DATE)

    @AfterTest
    fun tearDown() = runBlocking<Unit> { core.close() }

    private suspend fun fill() {
        mailStore.addFolder(1, "INBOX", "inbox")
        mail(1, "Rechnung Oktober", "kanzlei@steuer.example", "Wartung der Heizung", DAY_OCT_1)
        mail(2, "Rechnung Strom", "info@stadtwerke.example", "Abschlag fuer den Strom", DAY_SEP_1)
        mail(3, "Urlaubsplanung", "petra@firma.example", "Bitte tragt euch ein", DAY_OCT_1)
        indexer.indexChanges()
        semanticIndex.indexVectors { false }
    }

    @Test
    fun `chips filter the similar messages like the keyword hits`() = runBlocking<Unit> {
        fill()

        assertThat(similarIds(search("Kosten"))).containsExactlyInAnyOrder("m1@x.example", "m2@x.example")
        assertThat(similarIds(search("von:kanzlei@steuer.example Kosten"))).containsExactly("m1@x.example")
        assertThat(similarIds(search("Kosten seit 15.09.2026"))).containsExactly("m1@x.example")
        assertThat(similarIds(search("Kosten mit Anhang"))).containsExactly()
    }

    @Test
    fun `an old meaning hit counts less, unless the query asks for older mail`() = runBlocking<Unit> {
        fill()
        // a bill from ten years ago (similarity 1) and a recent statement that fits less well (similarity 0.7)
        mail(20, "Rechnung Garage", "vermieter@haus.example", "Miete fuer die Garage", day(10, 1, year = 2016))
        mail(21, "Abrechnung Keller", "vermieter@haus.example", "Strom im Keller", DAY_OCT_1)
        indexer.indexChanges()
        semanticIndex.indexVectors { false }
        val relevance = options.copy(sort = SearchSort.RELEVANCE)

        fun order(result: SearchResult) = similarIds(result).filter { it == "m20@x.example" || it == "m21@x.example" }

        assertThat(order(search.addSimilar("Kosten", search.search("Kosten", relevance), relevance)))
            .containsExactly("m21@x.example", "m20@x.example")
        val past = search.search("Kosten damals", relevance)
        assertThat(past.chips.map { it.id }).containsExactly("past", "term:0")
        assertThat(order(search.addSimilar("Kosten damals", past, relevance)))
            .containsExactly("m20@x.example", "m21@x.example")
    }

    @Test
    fun `best first rates keyword and meaning hits together, the rest by date`() = runBlocking<Unit> {
        fill()
        // keyword hits for "Kosten" with another topic: one in the subject (strong), five only in a long text
        mail(10, "Kosten Termin", "a@firma.example", "Termin fuer die Planung", DAY_SEP_1)
        for (id in 11L..15L) {
            mail(
                id,
                "Termin $id",
                "b@firma.example",
                "Termin mit Listen. " + FILLER.repeat(6) + " Kosten folgen.",
                DAY_OCT_1 + id,
            )
        }
        indexer.indexChanges()
        semanticIndex.indexVectors { false }
        val best = options.copy(sort = SearchSort.BEST)

        val result = search.addSimilar("Kosten", search.search("Kosten", best), best)

        // the two bills by meaning (similarity 1) and the subject hit on top
        assertThat(result.hits.take(result.bestCount).map { it.headerMessageId })
            .containsExactlyInAnyOrder("m1@x.example", "m2@x.example", "m10@x.example")
        val rest = result.hits.drop(result.bestCount)
        assertThat(rest.map { it.headerMessageId }).containsExactlyInAnyOrder(
            "m11@x.example",
            "m12@x.example",
            "m13@x.example",
            "m14@x.example",
            "m15@x.example",
        )
        assertThat(rest.map { it.date }).isEqualTo(rest.map { it.date }.sortedDescending())
    }

    @Test
    fun `a full store keeps the newest mails`() = runBlocking<Unit> {
        val small = SemanticIndex(core, { TopicBackend }, TestLogger(), maxVectors = 2)
        val smallIndexer = NovaBirdIndexer(core, mailStore, TestLogger(), small)
        mailStore.addFolder(1, "INBOX", "inbox")
        mail(1, "Rechnung Oktober", "kanzlei@steuer.example", "Heizung", DAY_OCT_1)
        mail(2, "Rechnung Strom", "info@stadtwerke.example", "Strom", DAY_SEP_1)
        mail(3, "Urlaubsplanung", "petra@firma.example", "Bitte eintragen", DAY_OCT_1 + 1000)
        smallIndexer.indexChanges()
        small.indexVectors { false }
        assertThat(vectorIds()).containsExactlyInAnyOrder("m1@x.example", "m3@x.example")
        assertThat(queueCount()).isEqualTo(0L)

        mail(4, "Rechnung Gas", "info@stadtwerke.example", "Gas", DAY_OCT_1 + 2000)
        smallIndexer.indexChanges()
        small.indexVectors { false }

        assertThat(vectorIds()).containsExactlyInAnyOrder("m3@x.example", "m4@x.example")
    }

    @Test
    fun `the vectors in memory come from all pages`() = runBlocking<Unit> {
        fill()
        core.engine().withMainDatabase { db ->
            db.execute(
                "INSERT INTO nb_vectors SELECT 'kopie' || rowid, model, h, scale, q FROM nb_vectors",
                JsonArray(emptyList()),
            )
        }

        val hits = semanticIndex.similar("Kosten", limit = 10).orEmpty().map { it.first }

        // both bills and their copies; the holiday mail is not similar
        assertThat(hits.size).isEqualTo(4)
    }

    @Test
    fun `vectors of deleted messages are removed after the next index run`() = runBlocking<Unit> {
        fill()
        mailStore.exec("UPDATE messages SET deleted = 1 WHERE id = 2")

        indexer.indexChanges()

        assertThat(vectorCount()).isEqualTo(2L)
        assertThat(similarIds(search("Kosten"))).containsExactly("m1@x.example")
    }

    private suspend fun search(text: String): SearchResult =
        search.addSimilar(text, search.search(text, options), options)

    private fun similarIds(result: SearchResult) = result.hits.filter { it.isSimilar }.map { it.headerMessageId }

    private suspend fun vectorIds(): List<String> = core.engine().withMainDatabase { db ->
        db.select("SELECT header_msg_id FROM nb_vectors", JsonArray(emptyList())).map {
            it.jsonArray[0].jsonPrimitive.content
        }
    }

    private suspend fun queueCount(): Long = core.engine().withMainDatabase { db ->
        db.select("SELECT count(*) FROM nb_vec_queue", JsonArray(emptyList())).single().jsonArray[0].jsonPrimitive.long
    }

    private suspend fun vectorCount(): Long = core.engine().withMainDatabase { db ->
        db.select("SELECT count(*) FROM nb_vectors", JsonArray(emptyList())).single().jsonArray[0].jsonPrimitive.long
    }

    private fun mail(id: Long, subject: String, sender: String, body: String, date: Long) {
        mailStore.insertMessage(
            id = id, folderId = 1, uid = "u$id", messageId = "<m$id@x.example>", subject = subject, date = date,
            sender = sender, to = "leitung@firma.example",
            root = TestPart(
                "text/plain",
                "Content-Type: text/plain; charset=utf-8\r\n",
                body = body.encodeToByteArray(),
            ),
        )
    }

    /** One dimension per topic: bills ("Rechnung", "Kosten"), holidays, everything else. */
    private object TopicBackend : NovaBirdVectorBackend {
        override val modelId = "topics"
        override fun isEnabled() = true
        override suspend fun embedQuery(text: String) = topic(text)
        override suspend fun embedForIndex(items: List<VectorItem>) = items.map { topic(it.text) }

        private fun topic(text: String): FloatArray {
            val t = text.lowercase()
            return when {
                "termin" in t -> floatArrayOf(0f, 0f, 1f)
                "abrechnung" in t -> floatArrayOf(0.7f, 0.7141428f, 0f)
                "rechnung" in t || "kosten" in t -> floatArrayOf(1f, 0f, 0f)
                "urlaub" in t -> floatArrayOf(0f, 1f, 0f)
                else -> floatArrayOf(0f, 0f, 1f)
            }
        }
    }

    private companion object {
        const val ACCOUNT = "3c1a5f7e-0000-4000-8000-00000000a002"

        fun day(month: Int, day: Int, year: Int = 2026) =
            java.time.ZonedDateTime.of(year, month, day, 9, 0, 0, 0, java.time.ZoneId.of("Europe/Berlin"))
                .toInstant().toEpochMilli()

        val NOW = day(10, 5)
        val DAY_OCT_1 = day(10, 1)
        val DAY_SEP_1 = day(9, 1)
        const val FILLER = "Dazu kommen Hinweise zu Raeumen, Zeiten, Wegen, Fahrten, Essen, Getraenken und Pausen. "
    }
}
