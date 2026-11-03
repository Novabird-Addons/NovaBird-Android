package com.fsck.k9.ui.settings

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.ui.settings.AboutContract.Effect
import com.fsck.k9.ui.settings.AboutContract.Event
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.common.provider.AppVersionProvider
import org.junit.Before
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class AboutViewModelTest {

    private lateinit var viewModel: AboutViewModel

    @Before
    fun setUp() {
        viewModel = AboutViewModel(
            appVersionProvider = mock<AppVersionProvider> {
                on { getVersionNumber() } doReturn "9.9.9"
            },
        )
    }

    @Test
    fun `initial state contains app version and libraries`() {
        val state = viewModel.state.value
        assertThat(state.version, "9.9.9")
        assertThat(state.libraries.isNotEmpty()).isEqualTo(true)
    }

    @Test
    fun `extra libraries of the app are added to the shared ones in name order`() {
        val extra = Library(name = "zz NovaBird Lib", url = "https://novabird.example", license = "MIT License")
        val withExtra = AboutViewModel(
            appVersionProvider = mock<AppVersionProvider> { on { getVersionNumber() } doReturn "9.9.9" },
            extraLibraries = listOf(extra, extra.copy(name = "AAA First")),
        )

        val libraries = withExtra.state.value.libraries
        assertThat(libraries.size).isEqualTo(viewModel.state.value.libraries.size + 2)
        assertThat(libraries.first().name).isEqualTo("AAA First")
        assertThat(libraries.last()).isEqualTo(extra)
    }

    @Test
    fun `OnChangeLogClick emits OpenChangeLog effect`() = runTest {
        viewModel.effect.test {
            viewModel.event(Event.OnChangeLogClick)

            assertThat(awaitItem()).isEqualTo(Effect.OpenChangeLog)
        }
    }

    @Test
    fun `OnSectionContentClick emits OpenUrl with correct url`() = runTest {
        val url = "https://example.com"

        viewModel.effect.test {
            viewModel.event(Event.OnSectionContentClick(url))

            assertThat(awaitItem()).isEqualTo(Effect.OpenUrl(url))
        }
    }

    @Test
    fun `OnLibraryClick emits OpenUrl with library url`() = runTest {
        val library = Library(
            name = "TestLib",
            url = "https://test.lib",
            license = "Apache",
        )

        viewModel.effect.test {
            viewModel.event(Event.OnLibraryClick(library))

            assertThat(Effect.OpenUrl("https://test.lib")).isEqualTo(awaitItem())
        }
    }
}
