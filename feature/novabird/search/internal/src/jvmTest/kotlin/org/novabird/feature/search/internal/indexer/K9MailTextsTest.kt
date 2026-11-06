package org.novabird.feature.search.internal.indexer

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
    fun `message ids of the mails that exist, like the index writes them`() = runBlocking {
        add(1, 1, hoursAgo = 2)
        store.insertMessage(
            id = 2, folderId = 1, uid = "u2", messageId = null, subject = "Ohne Message-ID",
            date = now, sender = "anna@example.com;Anna", to = "me@example.com", root = plain("Text"),
        )

        val ids = texts.headerMessageIds("acc-1", listOf(1L, 2L, 3L))

        assertThat(ids).isEqualTo(mapOf(1L to "m1@example.com", 2L to "k9-1-u2@novabird.invalid"))
        assertThat(texts.headerMessageIds("anderes-konto", listOf(1L))).isNull()
    }

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

    @Test
    fun `mail for the phishing check has all root headers decoded where Thunderbird decodes them, and all leaf parts`() =
        runBlocking {
            val root = TestPart(
                "multipart/mixed",
                "From: =?utf-8?Q?J=C3=BCrgen?= <j@example.com>\r\nSubject: =?utf-8?Q?Gr=C3=BC=C3=9Fe?=\r\n" +
                    "Authentication-Results: mx.example.com; dkim=pass header.d=example.com\r\n" +
                    "Content-Type: multipart/mixed; boundary=x\r\n",
                children = listOf(
                    TestPart(
                        "text/html",
                        "Content-Type: text/html; charset=utf-8\r\n",
                        "<p>Hallo</p>".encodeToByteArray(),
                    ),
                    TestPart(
                        "application/pdf",
                        "Content-Type: application/pdf\r\nContent-Disposition: attachment; filename=\"r.pdf\"\r\n",
                    ),
                ),
            )
            store.insertMessage(
                id = 9, folderId = 1, uid = "u9", messageId = "<m9@example.com>", subject = "Grüße", date = now,
                sender = "j@example.com", to = "me@example.com", root = root,
            )

            val mail = texts.mailForCheck("acc-1", 9)!!

            assertThat(mail.headers["from"]).isEqualTo(listOf("Jürgen <j@example.com>"))
            assertThat(mail.headers["subject"]).isEqualTo(listOf("Grüße"))
            assertThat(mail.headers["authentication-results"])
                .isEqualTo(listOf("mx.example.com; dkim=pass header.d=example.com"))
            assertThat(mail.parts.map { Triple(it.contentType, it.name, it.body) }).containsExactly(
                Triple("text/html", null, "<p>Hallo</p>"),
                Triple("application/pdf", "r.pdf", null),
            )
            assertThat(listOf(mail.folderType, mail.headerMessageId)).isEqualTo(listOf("inbox", "m9@example.com"))
        }

    @Test
    fun `stored parts can be read decoded, missing ones are not fetched`() = runBlocking {
        val root = TestPart(
            "multipart/related",
            "Content-Type: multipart/related; boundary=x\r\n",
            children = listOf(
                TestPart("text/html", "Content-Type: text/html\r\n", "<img src=\"cid:logo@x\">".encodeToByteArray()),
                TestPart(
                    "image/png",
                    "Content-Type: image/png; name=\"logo.png\"\r\nContent-ID: <logo@x>\r\n",
                    "iVBORw0K".encodeToByteArray(),
                    encoding = "base64",
                    onDisk = true,
                ),
                TestPart("application/zip", "Content-Type: application/zip; name=\"a.zip\"\r\n"),
            ),
        )
        store.insertMessage(
            id = 10, folderId = 1, uid = "u10", messageId = "<m10@example.com>", subject = "Bild", date = now,
            sender = "j@example.com", to = "me@example.com", root = root,
        )

        val (_, image, zip) = texts.mailForCheck("acc-1", 10)!!.parts

        assertThat(listOf(image.contentId, image.downloaded, zip.downloaded)).isEqualTo(listOf("logo@x", true, false))
        assertThat(texts.partBytes("acc-1", image.partId, 100)?.toList())
            .isEqualTo(listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a).map { it.toByte() })
        assertThat(texts.partBytes("acc-1", image.partId, 3)).isEqualTo(null)
        assertThat(texts.partBytes("acc-1", zip.partId, 100)).isEqualTo(null)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
