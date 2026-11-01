package org.novabird.feature.sync.limit

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.thunderbird.core.android.account.LegacyAccountDto
import org.novabird.feature.sync.settings.SyncLimitSettings
import org.novabird.feature.sync.settings.SyncLimitSettingsRepository

class SyncLimitSelectorTest {

    @Test
    fun `default limits are 512 KB on metered and 25 MB on unmetered networks`() {
        // Arrange
        val settings = SyncLimitSettings()

        // Act
        val metered = SyncLimitSelector.select(settings, isMetered = true)
        val unmetered = SyncLimitSelector.select(settings, isMetered = false)

        // Assert
        assertThat(metered).isEqualTo(512 * 1024)
        assertThat(unmetered).isEqualTo(25 * 1024 * 1024)
    }

    @Test
    fun `no limit is passed through as 0`() {
        // Arrange
        val settings = SyncLimitSettings(meteredLimit = 0, unmeteredLimit = 0)

        // Act
        val metered = SyncLimitSelector.select(settings, isMetered = true)
        val unmetered = SyncLimitSelector.select(settings, isMetered = false)

        // Assert
        assertThat(metered).isEqualTo(0)
        assertThat(unmetered).isEqualTo(0)
    }

    @Test
    fun `provider uses metered limit on metered network`() {
        // Arrange
        val testSubject = NetworkAwareSyncDownloadLimitProvider(
            settingsRepository = FakeSyncLimitSettingsRepository(SyncLimitSettings(1000, 2000)),
            networkState = { true },
        )

        // Act
        val result = testSubject.getMaximumAutoDownloadMessageSize(LegacyAccountDto(ACCOUNT_UUID))

        // Assert
        assertThat(result).isEqualTo(1000)
    }

    @Test
    fun `provider uses unmetered limit on unmetered network and ignores account setting`() {
        // Arrange
        val account = LegacyAccountDto(ACCOUNT_UUID).apply { maximumAutoDownloadMessageSize = 42 }
        val testSubject = NetworkAwareSyncDownloadLimitProvider(
            settingsRepository = FakeSyncLimitSettingsRepository(SyncLimitSettings(1000, 2000)),
            networkState = { false },
        )

        // Act
        val result = testSubject.getMaximumAutoDownloadMessageSize(account)

        // Assert
        assertThat(result).isEqualTo(2000)
    }
}

internal class FakeSyncLimitSettingsRepository(initial: SyncLimitSettings) : SyncLimitSettingsRepository {
    private val mutableSettings = MutableStateFlow(initial)
    override val settings: StateFlow<SyncLimitSettings> = mutableSettings

    override fun update(settings: SyncLimitSettings) {
        mutableSettings.value = settings
    }
}

internal const val ACCOUNT_UUID = "00000000-0000-4000-8000-000000000001"
