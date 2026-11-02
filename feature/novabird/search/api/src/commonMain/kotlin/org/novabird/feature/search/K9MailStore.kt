package org.novabird.feature.search

/**
 * Read-only access to the K-9 message database of one account (`<accountUuid>.db`, tables `messages`,
 * `message_parts`, `folders`). Implementations must never write.
 *
 * On Android, use the platform SQLite (the same library K-9 uses) for this file: a second SQLite copy in the same
 * process opening the same file can break POSIX locks (https://sqlite.org/howtocorrupt.html, section 2.2).
 */
interface K9MailDatabase : AutoCloseable {
    /** Runs a query; values are `Long`, `Double`, `String`, `ByteArray` or `null`. */
    fun query(sql: String, args: List<String> = emptyList()): List<List<Any?>>

    /** Body of a part stored on disk (`data_location = 2`), or `null` if missing. */
    fun readPartFile(partId: Long): ByteArray?
}

/** One mail account whose K-9 database should be indexed. */
data class K9Account(
    val uuid: String,
    val emails: List<String>,
)

/** Lists the accounts and opens their K-9 databases. */
interface K9MailStore {
    fun accounts(): List<K9Account>

    /** Opens the database of [accountUuid] read-only, or `null` if there is none (yet). */
    fun open(accountUuid: String): K9MailDatabase?
}

/** Accounts to index; bound by the app module (e.g. from the account manager). */
fun interface K9AccountProvider {
    fun accounts(): List<K9Account>
}

/** One address of a K-9 address list column (`sender_list`, `to_list`, …). */
data class K9Address(val email: String, val name: String?)

/** Unpacks the format of `Address.pack()` in K-9: `email;\u0001name,\u0001email…`, quotes in names escaped. */
fun unpackK9Addresses(packed: String?): List<K9Address> {
    if (packed.isNullOrEmpty()) return emptyList()
    return packed.split(",\u0001").filter { it.isNotEmpty() }.map { pair ->
        val separator = pair.indexOf(";\u0001")
        if (separator < 0) {
            K9Address(pair, null)
        } else {
            K9Address(pair.substring(0, separator), pair.substring(separator + 2).replace("\\\"", "\""))
        }
    }
}
