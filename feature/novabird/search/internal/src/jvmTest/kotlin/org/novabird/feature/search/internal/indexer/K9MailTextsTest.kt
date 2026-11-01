package org.novabird.feature.search.internal.indexer

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.novabird.feature.search.K9Account
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.internal.indexer.FakeK9MailStore.TestPart

class K9MailTextsTest {
    private val store = FakeK9MailStore(K9Account("acc-1", listOf("me@example.com"))).apply {
        addFolder(1, "INBOX", "inbox")
        addFolder(2, "Papierkorb", "trash")
    }
    private val now = 100 * HOUR
    private val texts = K9MailTexts(store) { now }

    private fun plain(body: String) =
        TestPart("text/plain", "Content-Type: text/plain; charset=utf-8\r\n", body.encodeToByteArray())

    private fun add(id: Long, folder: Long, hoursAgo: Long, read: Boolean = false) = store.insertMessage(
        id = id, folderId = folder, uid = "u$id", messageId = "<m$id@example.com>", subject = "Betreff $id",
        date = now - hoursAgo * HOUR, sender = "anna@example.com;\u0001Anna", to = "me@example.com",
        root = plain("Text $id"), read = read,
    )

    @Test
    fun `recent mails are the newest unread ones of the inboxes`() = runBlocking {
        add(1, 1, hoursAgo = 2)
        add(2, 1, hoursAgo = 1)
        add(3, 1, hoursAgo = 30)
        add(4, 2, hoursAgo = 1)
        add(5, 1, hoursAgo = 3, read = true)

        val recent = texts.recentMails(hours = 24, unreadOnly = true, max = 1)

        assertThat(recent.total).isEqualTo(2)
        assertThat(recent.mails.map { it.subject }).containsExactly("Betreff 2")
        assertThat(texts.recentMails(24, unreadOnly = false, max = 10).mails.map { it.location.uid })
            .containsExactly("u2", "u1", "u5")
    }

    @Test
    fun `mail has sender and text parts`() = runBlocking {
        add(7, 1, hoursAgo = 1)

        val mail = texts.mail(MessageLocation("acc-1", 1, "u7"))

        assertThat(mail).isNotNull()
        assertThat(listOf(mail!!.from, mail.plain, mail.html, mail.headerMessageId))
            .isEqualTo(listOf("Anna <anna@example.com>", "Text 7", "", "m7@example.com"))
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
