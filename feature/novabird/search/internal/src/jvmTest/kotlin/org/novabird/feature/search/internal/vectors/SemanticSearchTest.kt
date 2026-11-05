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
                "rechnung" in t || "kosten" in t -> floatArrayOf(1f, 0f, 0f)
                "urlaub" in t -> floatArrayOf(0f, 1f, 0f)
                else -> floatArrayOf(0f, 0f, 1f)
            }
        }
    }

    private companion object {
        const val ACCOUNT = "3c1a5f7e-0000-4000-8000-00000000a002"

        fun day(month: Int, day: Int) =
            java.time.ZonedDateTime.of(2026, month, day, 9, 0, 0, 0, java.time.ZoneId.of("Europe/Berlin"))
                .toInstant().toEpochMilli()

        val NOW = day(10, 5)
        val DAY_OCT_1 = day(10, 1)
        val DAY_SEP_1 = day(9, 1)
        const val FILLER = "Dazu kommen Hinweise zu Raeumen, Zeiten, Wegen, Fahrten, Essen, Getraenken und Pausen. "
    }
}
