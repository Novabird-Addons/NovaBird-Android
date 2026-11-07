package org.novabird.mail.account

import java.io.File

/**
 * Removes the database files of accounts that no longer exist. Thunderbird deletes them when an account is removed, but
 * something that still looks at the account a moment later creates an empty database again (seen in the stress test
 * 2026-11-07). They hold no mail, but stay forever; the app removes them at start, before any account can be set up.
 */
object OrphanAccountFiles {
    private val ACCOUNT_FILE = Regex(
        "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.db(-journal|-wal|-shm|_att)?$",
    )

    /** The files (and attachment folders) in [databaseDir] of accounts that are not in [accountUuids]. */
    fun orphans(databaseDir: File, accountUuids: Set<String>): List<File> =
        databaseDir.listFiles().orEmpty().filter { file ->
            val uuid = ACCOUNT_FILE.matchEntire(file.name)?.groupValues?.get(1)
            uuid != null && uuid !in accountUuids
        }

    fun removeOrphans(databaseDir: File, accountUuids: Set<String>): Int {
        val files = orphans(databaseDir, accountUuids)
        files.forEach { it.deleteRecursively() }
        return files.size
    }
}
