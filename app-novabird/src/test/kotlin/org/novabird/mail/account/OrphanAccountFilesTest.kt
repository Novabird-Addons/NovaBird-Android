package org.novabird.mail.account

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Test

class OrphanAccountFilesTest {
    private val dir: File = createTempDirectory("db").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `files of removed accounts go, the others stay`() {
        val kept = "11111111-2222-3333-4444-555555555555"
        val gone = "66666666-7777-8888-9999-000000000000"
        for (name in listOf("$kept.db", "$kept.db-journal", "$gone.db", "$gone.db-journal", "preferences_storage")) {
            File(dir, name).writeText("x")
        }
        File(dir, "$kept.db_att").mkdir()
        File(dir, "$gone.db_att").mkdir()
        File(dir, "$gone.db_att/42").writeText("x")

        val removed = OrphanAccountFiles.removeOrphans(dir, setOf(kept))

        assertThat(removed).isEqualTo(3)
        assertThat(dir.list().orEmpty().toList())
            .containsExactlyInAnyOrder("$kept.db", "$kept.db-journal", "$kept.db_att", "preferences_storage")
    }
}
