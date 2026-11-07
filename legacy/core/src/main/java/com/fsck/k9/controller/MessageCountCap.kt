package com.fsck.k9.controller

import android.database.sqlite.SQLiteDatabase
import java.util.Date

/**
 * NovaBird: a phone keeps at most [MAX_MESSAGES] messages per account, the newest ones. Above that the date of the
 * [MAX_MESSAGES]th newest message works like the sync period: the sync doesn't load older messages and removes the
 * local copies of the older ones (they stay on the server). Without a limit, 100,000 and more messages run the app out
 * of memory and storage.
 */
object MessageCountCap {
    const val MAX_MESSAGES = 100_000

    /** The date of the [max]th newest message of the account database, or `null` while it has fewer. */
    @JvmStatic
    fun capDate(db: SQLiteDatabase, max: Int): Date? = db.rawQuery(
        "SELECT date FROM messages WHERE empty = 0 AND deleted = 0 ORDER BY date DESC LIMIT 1 OFFSET ${max - 1}",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) Date(cursor.getLong(0)) else null
    }

    /** The later of the period's start and the limit's date. */
    @JvmStatic
    fun earliestDate(pollDate: Date?, capDate: Date?): Date? = when {
        pollDate == null -> capDate
        capDate == null -> pollDate
        else -> maxOf(pollDate, capDate)
    }
}
