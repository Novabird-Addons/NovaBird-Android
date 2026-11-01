package org.novabird.feature.sync.backfill

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.core.android.account.LegacyAccountDto
import org.novabird.feature.sync.limit.ACCOUNT_UUID

class PartialMessageBackfillSchedulerTest {
    private val enqueuer = FakeBackfillWorkEnqueuer()
    private val testSubject = PartialMessageBackfillScheduler(enqueuer)

    @Test
    fun `start schedules the periodic fallback and a one-time run`() {
        // Act
        testSubject.start()

        // Assert
        assertThat(enqueuer.periodic).isEqualTo(1)
        assertThat(enqueuer.oneTime).isEqualTo(1)
    }

    @Test
    fun `finished folder sync enqueues a one-time run`() {
        // Act
        testSubject.syncListener.synchronizeMailboxFinished(LegacyAccountDto(ACCOUNT_UUID), 1)

        // Assert
        assertThat(enqueuer.oneTime).isEqualTo(1)
        assertThat(enqueuer.periodic).isEqualTo(0)
    }
}

private class FakeBackfillWorkEnqueuer : BackfillWorkEnqueuer {
    var oneTime = 0
    var periodic = 0

    override fun enqueueOneTime() {
        oneTime++
    }

    override fun ensurePeriodic() {
        periodic++
    }
}
