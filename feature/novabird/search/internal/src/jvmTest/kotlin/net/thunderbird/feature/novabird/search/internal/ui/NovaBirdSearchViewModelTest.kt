package net.thunderbird.feature.novabird.search.internal.ui

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.feature.novabird.search.MessageLocation
import net.thunderbird.feature.novabird.search.NovaBirdSearch
import net.thunderbird.feature.novabird.search.SearchChip
import net.thunderbird.feature.novabird.search.SearchOptions
import net.thunderbird.feature.novabird.search.SearchResult
import net.thunderbird.feature.novabird.search.internal.ui.NovaBirdSearchContract.Effect
import net.thunderbird.feature.novabird.search.internal.ui.NovaBirdSearchContract.Event

@OptIn(ExperimentalCoroutinesApi::class)
class NovaBirdSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val search = FakeSearch()
    private val testSubject by lazy { NovaBirdSearchViewModel(search, TestLogger(), debounceMillis = 300) }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing searches once after the debounce`() = runTest(dispatcher) {
        testSubject.event(Event.QueryChanged("spei"))
        testSubject.event(Event.QueryChanged("speiseplan"))
        advanceUntilIdle()

        assertThat(search.queries).containsExactly("speiseplan")
        assertThat(testSubject.state.value.result?.parsedQuery).isEqualTo("q:speiseplan")
    }

    @Test
    fun `removing a chip searches without it`() = runTest(dispatcher) {
        testSubject.event(Event.QueryChanged("von Jonas im Juni"))
        advanceUntilIdle()

        testSubject.event(Event.RemoveChip("date"))
        advanceUntilIdle()

        assertThat(search.removedChips).containsExactly("q:von Jonas im Juni" to "date")
    }

    @Test
    fun `did you mean replaces the query`() = runTest(dispatcher) {
        testSubject.event(Event.QueryChanged("rechnnung"))
        advanceUntilIdle()

        testSubject.event(Event.ApplySuggestion)
        advanceUntilIdle()

        assertThat(testSubject.state.value.query).isEqualTo("rechnung")
        assertThat(search.queries).containsExactly("rechnnung", "rechnung")
    }

    @Test
    fun `errors are shown, not thrown`() = runTest(dispatcher) {
        search.fail = true
        testSubject.event(Event.Submit)
        advanceUntilIdle()

        assertThat(testSubject.state.value.hasError).isTrue()
    }

    @Test
    fun `opening a hit emits the location`() = runTest(dispatcher) {
        val location = MessageLocation("account", 3, "uid-7")

        testSubject.effect.test {
            testSubject.event(Event.OpenHit(location))

            assertThat(awaitItem()).isEqualTo(Effect.OpenMessage(location))
        }
    }

    private class FakeSearch : NovaBirdSearch {
        val queries = mutableListOf<String>()
        val removedChips = mutableListOf<Pair<String, String>>()
        var fail = false

        override suspend fun search(text: String, options: SearchOptions): SearchResult {
            if (fail) error("kaputt")
            queries += text
            return result(text, suggestion = if (text == "rechnnung") "rechnung" else null)
        }

        override suspend fun searchWithoutChip(
            previous: SearchResult,
            chipId: String,
            options: SearchOptions,
        ): SearchResult {
            removedChips += previous.parsedQuery to chipId
            return result("ohne $chipId", suggestion = null)
        }

        private fun result(text: String, suggestion: String?) = SearchResult(
            hits = emptyList(),
            chips = listOf(SearchChip("date", "date", "im Juni", isAmbiguous = false)),
            suggestion = suggestion,
            isEmptyQuery = text.isEmpty(),
            parsedQuery = "q:$text",
        )
    }
}
