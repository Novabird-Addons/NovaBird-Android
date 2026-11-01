package org.novabird.feature.sync.backfill

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import java.util.Date
import kotlin.test.Test

class PartialMessageSelectorTest {

    @Test
    fun `selects messages within limit and sync window, newest first`() {
        // Arrange
        val old = candidate(uid = "old", date = 500, size = 100)
        val small = candidate(uid = "small", date = 2000, size = 1_000)
        val exact = candidate(uid = "exact", date = 3000, size = 5_000)
        val tooLarge = candidate(uid = "large", date = 4000, size = 5_001)

        // Act
        val result = PartialMessageSelector.select(
            candidates = listOf(old, small, exact, tooLarge),
            maxSize = 5_000,
            earliestDate = Date(1000),
        )

        // Assert
        assertThat(result).containsExactly(exact, small)
    }

    @Test
    fun `message on the earliest date is included`() {
        // Arrange
        val onBoundary = candidate(uid = "boundary", date = 1000, size = 10)

        // Act
        val result = PartialMessageSelector.select(listOf(onBoundary), maxSize = 5_000, earliestDate = Date(1000))

        // Assert
        assertThat(result).containsExactly(onBoundary)
    }

    @Test
    fun `without sync window all dates are selected`() {
        // Arrange
        val veryOld = candidate(uid = "veryOld", date = 1, size = 10)

        // Act
        val result = PartialMessageSelector.select(listOf(veryOld), maxSize = 5_000, earliestDate = null)

        // Assert
        assertThat(result).containsExactly(veryOld)
    }

    @Test
    fun `message with unknown size is skipped when a limit is set`() {
        // Arrange
        val unknown = candidate(uid = "unknown", date = 2000, size = null)

        // Act
        val result = PartialMessageSelector.select(listOf(unknown), maxSize = 5_000, earliestDate = null)

        // Assert
        assertThat(result).isEmpty()
    }

    @Test
    fun `no limit selects large messages and messages with unknown size`() {
        // Arrange
        val unknown = candidate(uid = "unknown", date = 2000, size = null)
        val huge = candidate(uid = "huge", date = 3000, size = Long.MAX_VALUE)

        // Act
        val result = PartialMessageSelector.select(listOf(unknown, huge), maxSize = 0, earliestDate = null)

        // Assert
        assertThat(result).containsExactly(huge, unknown)
    }

    private fun candidate(uid: String, date: Long, size: Long?) = PartialMessageCandidate(
        folderServerId = "INBOX",
        messageServerId = uid,
        date = date,
        estimatedSize = size,
    )
}
