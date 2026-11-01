package org.novabird.feature.sync.integration

import app.k9mail.backend.testing.InMemoryBackendFolder
import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.backend.imap.ImapBackend
import com.fsck.k9.backend.imap.ImapPushConfigProvider
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.power.PowerManager
import com.fsck.k9.mail.power.WakeLock
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import com.fsck.k9.mail.store.imap.IdleRefreshTimer
import com.fsck.k9.mail.testing.message.buildMessage
import com.fsck.k9.mail.testing.security.TestTrustedSocketFactory
import com.fsck.k9.mail.transport.smtp.SmtpTransport
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.legacy.logging.Log
import org.novabird.feature.sync.backfill.PartialMessageBackfiller
import org.novabird.feature.sync.backfill.PartialMessageCandidate
import org.novabird.feature.sync.backfill.PartialMessageFinder
import org.novabird.feature.sync.limit.ACCOUNT_UUID
import org.novabird.feature.sync.limit.FakeSyncLimitSettingsRepository
import org.novabird.feature.sync.limit.NetworkAwareSyncDownloadLimitProvider
import org.novabird.feature.sync.settings.SyncLimitSettings

private const val FOLDER = "INBOX"
private const val UID_SMALL = "1"
private const val UID_LARGE = "2"
private const val UID_HUGE = "3"
private const val UID_OLD = "4"

private const val KIB = 1024
private const val MIB = 1024 * KIB

/**
 * Runs the real IMAP synchronization (`ImapSync` via [ImapBackend]) with the NovaBird limits against [FakeImapStore].
 *
 * Wi-Fi limit is 2 MB instead of 25 MB to keep the test messages small; the mobile limit is the default 512 KB.
 */
class SyncLimitsIntegrationTest {
    private val imapStore = FakeImapStore()
    private val imapFolder = imapStore.addFolder(FOLDER)
    private val backendStorage = InMemoryBackendStorage()
    private val backend = ImapBackend(
        accountName = "test",
        backendStorage = backendStorage,
        imapStore = imapStore,
        powerManager = FakePowerManager,
        idleRefreshManager = FakeIdleRefreshManager,
        pushConfigProvider = FakePushConfigProvider,
        smtpTransport = SmtpTransport(smtpSettings(), TestTrustedSocketFactory, oauthTokenProvider = null),
    )
    private val settings = FakeSyncLimitSettingsRepository(
        SyncLimitSettings(meteredLimit = SyncLimitSettings.DEFAULT_METERED_LIMIT, unmeteredLimit = 2 * MIB),
    )
    private var isMetered = true
    private val limitProvider = NetworkAwareSyncDownloadLimitProvider(settings, networkState = { isMetered })
    private val account = LegacyAccountDto(ACCOUNT_UUID).apply {
        maximumPolledMessageAge = 365
        maximumAutoDownloadMessageSize = 0 // must be ignored
    }

    private lateinit var backendFolder: InMemoryBackendFolder

    @BeforeTest
    fun setUp() {
        Log.logger = TestLogger()
        backendStorage.createFolderUpdater().use { updater ->
            updater.createFolders(listOf(FolderInfo(serverId = FOLDER, name = FOLDER, type = FolderType.INBOX)))
        }
        backendFolder = backendStorage.getFolder(FOLDER)

        imapFolder.addMessage(UID_SMALL.toLong(), messageWithAttachment(daysAgo = 3, attachmentSize = 100 * KIB))
        imapFolder.addMessage(UID_LARGE.toLong(), messageWithAttachment(daysAgo = 2, attachmentSize = 1 * MIB))
        imapFolder.addMessage(UID_HUGE.toLong(), messageWithAttachment(daysAgo = 1, attachmentSize = 3 * MIB))
        imapFolder.addMessage(UID_OLD.toLong(), messageWithAttachment(daysAgo = 2 * 365, attachmentSize = 10 * KIB))
    }

    @Test
    fun `mobile sync downloads small messages completely, large ones partially and none outside the sync period`() {
        // Arrange
        isMetered = true

        // Act
        sync()

        // Assert
        assertThat(backendFolder.getMessageServerIds()).containsExactlyInAnyOrder(UID_SMALL, UID_LARGE, UID_HUGE)
        assertThat(backendFolder.getMessageFlags(UID_SMALL)).contains(Flag.X_DOWNLOADED_FULL)
        assertThat(backendFolder.getMessageFlags(UID_LARGE)).contains(Flag.X_DOWNLOADED_PARTIAL)
        assertThat(backendFolder.getMessageFlags(UID_HUGE)).contains(Flag.X_DOWNLOADED_PARTIAL)
    }

    @Test
    fun `sync on Wi-Fi uses the Wi-Fi limit`() {
        // Arrange
        isMetered = false

        // Act
        sync()

        // Assert
        assertThat(backendFolder.getMessageFlags(UID_SMALL)).contains(Flag.X_DOWNLOADED_FULL)
        assertThat(backendFolder.getMessageFlags(UID_LARGE)).contains(Flag.X_DOWNLOADED_FULL)
        assertThat(backendFolder.getMessageFlags(UID_HUGE)).contains(Flag.X_DOWNLOADED_PARTIAL)
    }

    @Test
    fun `backfill on Wi-Fi completes partial messages within the Wi-Fi limit`() {
        // Arrange
        isMetered = true
        sync()
        isMetered = false
        val transfersBefore = imapFolder.bodyTransfers.toMap()
        val testSubject = PartialMessageBackfiller(
            accounts = { listOf(account) },
            finder = InMemoryPartialMessageFinder(backendFolder, imapFolder),
            downloader = { _, folderServerId, messageServerId ->
                backend.downloadCompleteMessage(folderServerId, messageServerId)
            },
            settingsRepository = settings,
            logger = TestLogger(),
        )

        // Act
        val result = testSubject.run(isStopped = { false })

        // Assert
        assertThat(result.downloaded).isEqualTo(1)
        assertThat(backendFolder.getMessageFlags(UID_LARGE)).contains(Flag.X_DOWNLOADED_FULL)
        assertThat(backendFolder.getMessageFlags(UID_LARGE)).doesNotContain(Flag.X_DOWNLOADED_PARTIAL)
        assertThat(backendFolder.getMessageFlags(UID_HUGE)).contains(Flag.X_DOWNLOADED_PARTIAL)
        assertThat(imapFolder.bodyTransfers.getValue(UID_LARGE)).isGreaterThan(transfersBefore.getValue(UID_LARGE))
        assertThat(imapFolder.bodyTransfers.getValue(UID_HUGE)).isEqualTo(transfersBefore.getValue(UID_HUGE))
        assertThat(backendFolder.getMessageServerIds()).doesNotContain(UID_OLD)
    }

    @Test
    fun `size estimate from the structure is close to the server size`() {
        // Arrange
        val serverSize = imapFolder.serverSize(UID_LARGE)

        // Act
        val estimate = imapFolder.structureSize(UID_LARGE)

        // Assert: headers aren't included in the estimate
        assertThat(serverSize - estimate).isGreaterThan(0L)
        assertThat(serverSize - estimate < 2 * KIB).isEqualTo(true)
    }

    private fun sync() {
        val syncConfig = SyncConfig(
            expungePolicy = SyncConfig.ExpungePolicy.MANUALLY,
            earliestPollDate = account.earliestPollDate,
            syncRemoteDeletions = true,
            maximumAutoDownloadMessageSize = limitProvider.getMaximumAutoDownloadMessageSize(account),
            defaultVisibleLimit = 25,
            syncFlags = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED),
        )
        backend.sync(FOLDER, syncConfig, NoOpSyncListener)
    }

    private fun messageWithAttachment(daysAgo: Int, attachmentSize: Int): Message {
        val date = Calendar.getInstance().apply { add(Calendar.DATE, -daysAgo) }.time
        return buildMessage {
            header("Subject", "Test")
            header("From", "alice@domain.example")
            header("To", "bob@domain.example")
            header("Date", SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(date))
            header("Message-ID", "<msg-$daysAgo-$attachmentSize@domain.example>")
            multipart {
                bodyPart("text/plain; charset=utf-8") { textBody("Hello") }
                bodyPart("application/octet-stream") { dataBody(size = attachmentSize) }
            }
        }
    }

    private fun smtpSettings() = ServerSettings(
        type = "smtp",
        host = "smtp.domain.example",
        port = 587,
        connectionSecurity = ConnectionSecurity.STARTTLS_REQUIRED,
        authenticationType = AuthType.PLAIN,
        username = "user",
        password = "irrelevant",
        clientCertificateAlias = null,
    )
}

/**
 * Builds candidates like `LocalStorePartialMessageFinder`: size = sum of part sizes reported by the structure.
 */
private class InMemoryPartialMessageFinder(
    private val backendFolder: InMemoryBackendFolder,
    private val imapFolder: FakeImapFolder,
) : PartialMessageFinder {
    override fun findPartialMessages(account: LegacyAccountDto, earliestDate: Date?): List<PartialMessageCandidate> {
        val dates = backendFolder.getAllMessagesAndEffectiveDates()
        return backendFolder.getMessageServerIds()
            .filter { Flag.X_DOWNLOADED_PARTIAL in backendFolder.getMessageFlags(it) }
            .map { uid ->
                PartialMessageCandidate(
                    folderServerId = FOLDER,
                    messageServerId = uid,
                    date = dates.getValue(uid) ?: 0L,
                    estimatedSize = imapFolder.structureSize(uid),
                )
            }
    }
}

private object NoOpSyncListener : SyncListener {
    override fun syncStarted(folderServerId: String) = Unit
    override fun syncAuthenticationSuccess() = Unit
    override fun syncHeadersStarted(folderServerId: String) = Unit
    override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = Unit
    override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) = Unit
    override fun syncProgress(folderServerId: String, completed: Int, total: Int) = Unit
    override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) = Unit
    override fun syncRemovedMessage(folderServerId: String, messageServerId: String) = Unit
    override fun syncFlagChanged(folderServerId: String, messageServerId: String) = Unit
    override fun syncFinished(folderServerId: String) = Unit
    override fun syncFailed(folderServerId: String, message: String, exception: Exception?) = error(message)
    override fun folderStatusChanged(folderServerId: String) = Unit
}

private object FakePowerManager : PowerManager {
    override fun newWakeLock(tag: String): WakeLock = error("not used")
}

private object FakeIdleRefreshManager : IdleRefreshManager {
    override fun startTimer(timeout: Long, callback: () -> Unit): IdleRefreshTimer = error("not used")
    override fun resetTimers() = Unit
}

private object FakePushConfigProvider : ImapPushConfigProvider {
    override val maxPushFoldersFlow = flowOf(0)
    override val idleRefreshMinutesFlow = flowOf(0)
}
