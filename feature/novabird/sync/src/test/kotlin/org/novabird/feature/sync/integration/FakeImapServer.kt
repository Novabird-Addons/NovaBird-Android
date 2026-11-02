package org.novabird.feature.sync.integration

import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageRetrievalListener
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MimeBodyPart
import com.fsck.k9.mail.internet.MimeMultipart
import com.fsck.k9.mail.store.imap.FetchListener
import com.fsck.k9.mail.store.imap.FolderListItem
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapMessage
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.OpenMode
import java.io.OutputStream
import java.util.Date
import net.thunderbird.core.common.mail.Flag

/**
 * In-process IMAP store that behaves like a server for what the sync limits depend on:
 * - `ENVELOPE` reports the message size (`RFC822.SIZE`).
 * - `STRUCTURE` returns the MIME structure without bodies; parts carry `size=` like `RealImapFolder` adds from
 *   `BODYSTRUCTURE`.
 * - [ImapFolder.fetchPart] downloads a single part.
 * - [ImapFolder.getMessages] honors `earliestDate` like `SEARCH SINCE`.
 */
internal class FakeImapStore : ImapStore {
    private val folders = mutableMapOf<String, FakeImapFolder>()

    override val combinedPrefix: String? = null

    fun addFolder(serverId: String): FakeImapFolder {
        return FakeImapFolder(serverId).also { folders[serverId] = it }
    }

    override fun getFolder(name: String): ImapFolder = folders[name] ?: error("Folder not found")

    override fun getFolders(): List<FolderListItem> {
        return folders.values.map {
            FolderListItem(serverId = it.serverId, name = it.serverId, type = FolderType.INBOX)
        }
    }

    override fun checkSettings() = Unit

    override fun closeAllConnections() = Unit

    override fun fetchImapPrefix() = Unit
}

@Suppress("TooManyFunctions")
internal class FakeImapFolder(override val serverId: String) : ImapFolder {
    private val messages = sortedMapOf<Long, Message>()

    /** Number of times a message body (complete or partial) was transferred, per UID. */
    val bodyTransfers = mutableMapOf<String, Int>()

    override var mode: OpenMode? = null
        private set

    override val isOpen: Boolean
        get() = mode != null

    override val messageCount: Int
        get() = messages.size

    fun addMessage(uid: Long, message: Message) {
        messages[uid] = message
    }

    fun serverSize(uid: String): Long = messageFor(uid).partSize()

    /**
     * Sum of the part sizes from `BODYSTRUCTURE`, i.e. what the local store records for a partially downloaded message.
     */
    fun structureSize(uid: String): Long {
        val body = messageFor(uid).body
        return if (body is Multipart) body.bodyParts.sumOf { it.body.bodySize() } else messageFor(uid).partSize()
    }

    override fun open(mode: OpenMode) {
        this.mode = mode
    }

    override fun close() {
        mode = null
    }

    override fun exists(): Boolean = true

    override fun getUidValidity(): Long? = 1L

    override fun getMessage(uid: String): ImapMessage = ImapMessage(uid)

    override fun getUidFromMessageId(messageId: String): String? = null

    override fun getMessages(
        start: Int,
        end: Int,
        earliestDate: Date?,
        listener: MessageRetrievalListener<ImapMessage>?,
    ): List<ImapMessage> {
        return messages.entries.toList()
            .sortedByDescending { it.key }
            .slice((start - 1) until end)
            .filter { (_, message) -> earliestDate == null || !message.sentDate.before(earliestDate) }
            .map { (uid, _) -> ImapMessage(uid.toString()) }
    }

    override fun areMoreMessagesAvailable(indexOfOldestMessage: Int, earliestDate: Date?): Boolean = false

    override suspend fun fetch(
        messages: List<ImapMessage>,
        fetchProfile: FetchProfile,
        listener: FetchListener?,
        maxDownloadSize: Int,
    ) {
        for (imapMessage in messages) {
            val stored = messageFor(imapMessage.uid)

            if (FetchProfile.Item.ENVELOPE in fetchProfile) {
                copyHeaders(stored, imapMessage)
                imapMessage.setSize(stored.partSize().toInt())
            }

            if (FetchProfile.Item.STRUCTURE in fetchProfile) {
                imapMessage.body = createStructure(stored)
            }

            if (FetchProfile.Item.BODY in fetchProfile || FetchProfile.Item.BODY_SANE in fetchProfile) {
                imapMessage.headers.map { it.name }.distinct().forEach { imapMessage.removeHeader(it) }
                copyHeaders(stored, imapMessage)
                imapMessage.body = stored.body
                bodyTransfers.merge(imapMessage.uid, 1, Int::plus)
            }

            listener?.onFetchResponse(imapMessage, isFirstResponse = true)
        }
    }

    override fun fetchPart(message: ImapMessage, part: Part, bodyFactory: BodyFactory, maxDownloadSize: Int) {
        val stored = messageFor(message.uid).body as Multipart
        val index = part.serverExtra.toInt() - 1
        part.body = stored.getBodyPart(index).body
        bodyTransfers.merge(message.uid, 1, Int::plus)
    }

    override fun search(
        queryString: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<ImapMessage> = emptyList()

    override fun appendMessages(messages: List<Message>): Map<String, String>? = null

    override fun setFlagsForAllMessages(flags: Set<Flag>, value: Boolean) = Unit

    override fun setFlags(messages: List<ImapMessage>, flags: Set<Flag>, value: Boolean) = Unit

    override fun copyMessages(messages: List<ImapMessage>, folder: ImapFolder): Map<String, String>? = null

    override fun moveMessages(messages: List<ImapMessage>, folder: ImapFolder): Map<String, String>? = null

    override fun deleteMessages(messages: List<ImapMessage>) = Unit

    override fun deleteAllMessages() = Unit

    override fun expunge() = Unit

    override fun expungeUids(uids: List<String>) = Unit

    override fun create(folderType: FolderType): Boolean = false

    private fun messageFor(uid: String): Message = messages[uid.toLong()] ?: error("Message not found")

    private fun copyHeaders(source: Message, destination: Part) {
        for (header in source.headers) {
            destination.addHeader(header.name, header.value)
        }
    }

    private fun createStructure(stored: Message): Multipart? {
        val storedMultipart = stored.body as? Multipart ?: return null

        return MimeMultipart(storedMultipart.boundary).apply {
            setSubType("mixed")
            storedMultipart.bodyParts.forEachIndexed { index, storedPart ->
                val part = MimeBodyPart()
                for (name in listOf("Content-Type", "Content-Transfer-Encoding")) {
                    storedPart.getHeader(name).forEach { part.addHeader(name, it) }
                }
                if (!storedPart.mimeType.startsWith("text/")) {
                    part.setHeader("Content-Disposition", "attachment; size=${storedPart.body.bodySize()}")
                }
                part.serverExtra = (index + 1).toString()
                addBodyPart(part)
            }
        }
    }
}

private fun Message.partSize(): Long = CountingOutputStream().also { writeTo(it) }.count

private fun com.fsck.k9.mail.Body.bodySize(): Long = CountingOutputStream().also { writeTo(it) }.count

private class CountingOutputStream : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        count += len
    }
}
