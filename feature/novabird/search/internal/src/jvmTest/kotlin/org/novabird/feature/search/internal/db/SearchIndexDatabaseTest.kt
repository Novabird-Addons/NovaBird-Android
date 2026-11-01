package org.novabird.feature.search.internal.db

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class SearchIndexDatabaseTest {
    private val testSubject = SearchIndexDatabase(":memory:")

    @AfterTest
    fun tearDown() {
        testSubject.close()
    }

    // Same probes as novabird-search/db/src/db/capabilities.js, plus fts5vocab (needed for "Meinten Sie").
    @Test
    fun `bundled sqlite supports fts5, contentless_delete, trigram and fts5vocab`() {
        val probes = listOf(
            "CREATE VIRTUAL TABLE temp.cap_fts5 USING fts5(x)",
            "CREATE VIRTUAL TABLE temp.cap_cd USING fts5(x, content='', contentless_delete=1)",
            "CREATE VIRTUAL TABLE temp.cap_tri USING fts5(x, tokenize='trigram')",
            "CREATE VIRTUAL TABLE temp.cap_fts4 USING fts4(x)",
            "CREATE VIRTUAL TABLE temp.cap_vocab USING fts5vocab(cap_fts5, 'row')",
        )

        for (sql in probes) {
            testSubject.execute(sql, JsonArray(emptyList()))
        }

        val version = testSubject.select("SELECT sqlite_version()", JsonArray(emptyList()))
            .single().jsonArray[0].jsonPrimitive.content
        val (major, minor) = version.split('.').map { it.toInt() }
        assertThat(major * 1000 + minor).isGreaterThanOrEqualTo(3043) // contentless_delete needs 3.43
        val options = testSubject.select("PRAGMA compile_options", JsonArray(emptyList()))
            .map { it.jsonArray[0].jsonPrimitive.content }
        assertThat(options.contains("ENABLE_FTS5")).isTrue()
    }

    @Test
    fun `trigram finds compound parts and contentless delete removes rows`() {
        testSubject.execute(
            "CREATE VIRTUAL TABLE t USING fts5(s, content='', contentless_delete=1, tokenize='trigram')",
            JsonArray(emptyList()),
        )
        testSubject.batch(
            buildJsonArray {
                add(statement("INSERT INTO t(rowid, s) VALUES (1, ?)", JsonPrimitive("wochenspeiseplane kw 40")))
                add(statement("INSERT INTO t(rowid, s) VALUES (2, ?)", JsonPrimitive("stundenplan")))
            },
        )

        val hits = testSubject.select("SELECT rowid FROM t WHERE t MATCH ?", params(JsonPrimitive("\"speiseplan\"")))
        testSubject.execute("DELETE FROM t WHERE rowid = ?", params(JsonPrimitive(1)))
        val afterDelete = testSubject.select(
            "SELECT count(*) FROM t WHERE t MATCH ?",
            params(JsonPrimitive("\"plan\"")),
        )

        assertThat(hits.map { it.jsonArray[0].jsonPrimitive.long }).isEqualTo(listOf(1L))
        assertThat(afterDelete.single().jsonArray[0].jsonPrimitive.long).isEqualTo(1L)
    }

    @Test
    fun `batch is rolled back as a whole on error`() {
        testSubject.execute("CREATE TABLE x (v INTEGER NOT NULL)", JsonArray(emptyList()))

        runCatching {
            testSubject.batch(
                buildJsonArray {
                    add(statement("INSERT INTO x VALUES (?)", JsonPrimitive(1)))
                    add(statement("INSERT INTO x VALUES (?)", JsonPrimitive(null as String?)))
                },
            )
        }

        val count = testSubject.select("SELECT count(*) FROM x", JsonArray(emptyList()))
        assertThat(count.single().jsonArray[0].jsonPrimitive.long).isEqualTo(0L)
    }

    @Test
    fun `large integers stay integers`() {
        val date = 1_790_000_000_000L
        testSubject.execute("CREATE TABLE d (v)", JsonArray(emptyList()))
        testSubject.execute("INSERT INTO d VALUES (?)", params(JsonPrimitive(1.79e12)))

        val row = testSubject.select("SELECT v, typeof(v) FROM d", JsonArray(emptyList())).single().jsonArray

        assertThat(row[0].jsonPrimitive.long).isEqualTo(date)
        assertThat(row[1].jsonPrimitive.content).isEqualTo("integer")
    }

    private fun params(vararg values: JsonPrimitive) = JsonArray(values.toList())

    private fun statement(sql: String, vararg values: JsonPrimitive) =
        JsonArray(listOf(JsonPrimitive(sql), JsonArray(values.toList())))
}
