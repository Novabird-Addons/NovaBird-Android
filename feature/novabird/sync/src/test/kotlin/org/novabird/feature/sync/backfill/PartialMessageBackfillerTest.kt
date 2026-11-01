package org.novabird.feature.sync.backfill

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.util.Date
import kotlin.test.Test
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.logging.testing.TestLogger
import org.novabird.feature.sync.limit.ACCOUNT_UUID
import org.novabird.feature.sync.limit.FakeSyncLimitSettingsRepository
import org.novabird.feature.sync.settings.SyncLimitSettings

class PartialMessageBackfillerTest {
    private val finder = FakePartialMessageFinder()
    private val downloader = FakeCompleteMessageDownloader()

    @Test
    fun `downloads selected messages using the unmetered limit and the account sync window`() {
        // Arrange
        val account = LegacyAccountDto(ACCOUNT_UUID).apply { maximumPolledMessageAge = 365 }
        finder.candidates = listOf(
            candidate(uid = "fits", size = 2_000),
            candidate(uid = "tooLarge", size = 2_001),
        )
        val testSubject = createTestSubject(listOf(account), unmeteredLimit = 2_000)

        // Act
        val result = testSubject.run(isStopped = { false })

        // Assert
        assertThat(downloader.downloaded).containsExactly("fits")
        assertThat(finder.requestedEarliestDate).isEqualTo(account.earliestPollDate)
        assertThat(result).isEqualTo(BackfillResult(downloaded = 1, failed = 0, stopped = false))
    }

    @Test
    fun `account without sync window passes no earliest date`() {
        // Arrange
        val account = LegacyAccountDto(ACCOUNT_UUID).apply { maximumPolledMessageAge = -1 }
        finder.candidates = emptyList()
        val testSubject = createTestSubject(listOf(account), unmeteredLimit = 2_000)

        // Act
        testSubject.run(isStopped = { false })

        // Assert
        assertThat(finder.requestedEarliestDate).isNull()
    }

    @Test
    fun `failed download is counted and does not stop the backfill`() {
        // Arrange
        finder.candidates = listOf(candidate(uid = "a", size = 10), candidate(uid = "b", size = 10))
        downloader.failingUids = setOf("a")
        val testSubject = createTestSubject(listOf(LegacyAccountDto(ACCOUNT_UUID)), unmeteredLimit = 2_000)

        // Act
        val result = testSubject.run(isStopped = { false })

        // Assert
        assertThat(downloader.downloaded).containsExactly("b")
        assertThat(result).isEqualTo(BackfillResult(downloaded = 1, failed = 1, stopped = false))
    }

    @Test
    fun `stops when the worker is stopped`() {
        // Arrange
        finder.candidates = listOf(candidate(uid = "a", size = 10))
        val testSubject = createTestSubject(listOf(LegacyAccountDto(ACCOUNT_UUID)), unmeteredLimit = 2_000)

        // Act
        val result = testSubject.run(isStopped = { true })

        // Assert
        assertThat(downloader.downloaded).containsExactly()
        assertThat(result).isEqualTo(BackfillResult(downloaded = 0, failed = 0, stopped = true))
    }

    private fun createTestSubject(accounts: List<LegacyAccountDto>, unmeteredLimit: Int) = PartialMessageBackfiller(
        accounts = { accounts },
        finder = finder,
        downloader = downloader,
        settingsRepository = FakeSyncLimitSettingsRepository(SyncLimitSettings(unmeteredLimit = unmeteredLimit)),
        logger = TestLogger(),
    )

    private fun candidate(uid: String, size: Long?) = PartialMessageCandidate(
        folderServerId = "INBOX",
        messageServerId = uid,
        date = System.currentTimeMillis(),
        estimatedSize = size,
    )
}

private class FakePartialMessageFinder : PartialMessageFinder {
    var candidates: List<PartialMessageCandidate> = emptyList()
    var requestedEarliestDate: Date? = null

    override fun findPartialMessages(account: LegacyAccountDto, earliestDate: Date?): List<PartialMessageCandidate> {
        requestedEarliestDate = earliestDate
        return candidates
    }
}

private class FakeCompleteMessageDownloader : CompleteMessageDownloader {
    val downloaded = mutableListOf<String>()
    var failingUids: Set<String> = emptySet()

    override fun downloadCompleteMessage(account: LegacyAccountDto, folderServerId: String, messageServerId: String) {
        if (messageServerId in failingUids) error("Download failed")
        downloaded += messageServerId
    }
}
