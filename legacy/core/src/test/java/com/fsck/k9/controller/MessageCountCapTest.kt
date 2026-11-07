package com.fsck.k9.controller

import android.database.sqlite.SQLiteDatabase
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.K9RobolectricTest
import java.util.Date
import org.junit.After
import org.junit.Test

class MessageCountCapTest : K9RobolectricTest() {
    private val db = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE messages (date INTEGER, empty INTEGER, deleted INTEGER)")
        for (date in 1..5) execSQL("INSERT INTO messages VALUES ($date, 0, 0)")
        execSQL("INSERT INTO messages VALUES (9, 0, 1)")
        execSQL("INSERT INTO messages VALUES (8, 1, 0)")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the date of the last message that still counts`() {
        assertThat(MessageCountCap.capDate(db, max = 3)).isEqualTo(Date(3))
        assertThat(MessageCountCap.capDate(db, max = 5)).isEqualTo(Date(1))
    }

    @Test
    fun `no date while there are fewer messages, deleted and placeholders don't count`() {
        assertThat(MessageCountCap.capDate(db, max = 6)).isNull()
    }

    @Test
    fun `the later date wins`() {
        assertThat(MessageCountCap.earliestDate(Date(5), Date(3))).isEqualTo(Date(5))
        assertThat(MessageCountCap.earliestDate(Date(2), Date(3))).isEqualTo(Date(3))
        assertThat(MessageCountCap.earliestDate(null, Date(3))).isEqualTo(Date(3))
        assertThat(MessageCountCap.earliestDate(Date(2), null)).isEqualTo(Date(2))
        assertThat(MessageCountCap.earliestDate(null, null)).isNull()
    }
}
