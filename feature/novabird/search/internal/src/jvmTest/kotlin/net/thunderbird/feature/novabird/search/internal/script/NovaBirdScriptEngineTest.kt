package net.thunderbird.feature.novabird.search.internal.script

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.thunderbird.feature.novabird.search.internal.db.SearchIndexDatabase

class NovaBirdScriptEngineTest {

    @Test
    fun `migrate detects fts5-contentless with trigram under QuickJS`() = runTest {
        SearchIndexDatabase(":memory:").use { database ->
            NovaBirdScriptEngine.create(ScriptSources.load(), database, Dispatchers.Default.limitedParallelism(1))
                .use { testSubject ->
                    val caps = Json.parseToJsonElement(testSubject.call("migrate")!!).jsonObject

                    assertThat(caps["ftsMode"]!!.jsonPrimitive.content).isEqualTo("fts5-contentless")
                    assertThat(caps["trigram"]!!.jsonPrimitive.boolean).isEqualTo(true)
                    assertThat(caps["contentlessDelete"]!!.jsonPrimitive.boolean).isEqualTo(true)
                }
        }
    }

    @Test
    fun `module names resolve relative to the importing module`() {
        assertThat(
            NovaBirdScriptEngine.resolveModuleName("novabird-android/glue.js", "../novabird-search/src/search.js", emptyMap()),
        ).isEqualTo("novabird-search/src/search.js")
        assertThat(
            NovaBirdScriptEngine.resolveModuleName("novabird-search/src/query/buildSql.js", "../util/normalize.js", emptyMap()),
        ).isEqualTo("novabird-search/src/util/normalize.js")
        assertThat(NovaBirdScriptEngine.resolveModuleName("a/b.js", "vitest", mapOf("vitest" to "t/v.js"))).isEqualTo("t/v.js")
    }
}
