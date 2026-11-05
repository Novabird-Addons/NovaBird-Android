package org.novabird.feature.search.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.thunderbird.components.ui.bolt.atom.ClickableSurface
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.SearchChip
import org.novabird.feature.search.SearchHit
import org.novabird.feature.search.internal.resources.Res
import org.novabird.feature.search.internal.resources.novabird_search_best_count
import org.novabird.feature.search.internal.resources.novabird_search_did_you_mean
import org.novabird.feature.search.internal.resources.novabird_search_error
import org.novabird.feature.search.internal.resources.novabird_search_hint
import org.novabird.feature.search.internal.resources.novabird_search_more_by_date
import org.novabird.feature.search.internal.resources.novabird_search_no_results
import org.novabird.feature.search.internal.resources.novabird_search_similar_busy
import org.novabird.feature.search.internal.resources.novabird_search_similar_count
import org.novabird.feature.search.internal.resources.novabird_search_similar_label
import org.novabird.feature.search.internal.resources.novabird_search_title
import org.novabird.feature.search.internal.ui.NovaBirdSearchContract.Effect
import org.novabird.feature.search.internal.ui.NovaBirdSearchContract.Event
import org.novabird.feature.search.internal.ui.NovaBirdSearchContract.State

/**
 * Entry point of the NovaBird search screen; the app module shows it and opens hits in the existing message view.
 *
 * @param onOpenMessage called with the K-9 location of a tapped hit.
 * @param initialQuery query to search for when the screen opens (e.g. typed into the search field of the message
 *   list), unless the screen already has a query.
 */
@Composable
fun NovaBirdSearchScreen(
    onOpenMessage: (MessageLocation) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String? = null,
) {
    val viewModel: NovaBirdSearchViewModel = koinViewModel()
    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            is Effect.OpenMessage -> onOpenMessage(effect.location)
            Effect.NavigateBack -> onBack()
        }
    }
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank() && state.value.query.isEmpty()) {
            dispatch(Event.QueryChanged(initialQuery))
            dispatch(Event.Submit)
        }
    }
    NovaBirdSearchContent(state = state.value, onEvent = dispatch, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NovaBirdSearchContent(
    state: State,
    onEvent: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    // the top app bar takes the status bar inset, the scaffold the remaining system bars
    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(Res.string.novabird_search_title),
                onBackClick = { onEvent(Event.Back) },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = BoltTheme.spacings.double),
        ) {
            TextInput(
                onTextChange = { onEvent(Event.QueryChanged(it)) },
                text = state.query,
                label = stringResource(Res.string.novabird_search_hint),
                // autocorrect would turn "naechsten" into "nächsten" or replace names and rare words
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )

            val result = state.result
            if (result != null && result.chips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    result.chips.forEach { chip ->
                        Chip(chip = chip, onRemove = { onEvent(Event.RemoveChip(chip.id)) })
                    }
                }
            }
            result?.suggestion?.let { suggestion ->
                ButtonText(
                    text = stringResource(Res.string.novabird_search_did_you_mean, suggestion),
                    onClick = { onEvent(Event.ApplySuggestion) },
                )
            }
            when {
                state.hasError -> TextBodyMedium(text = stringResource(Res.string.novabird_search_error))

                result != null && result.hits.isEmpty() && !result.isEmptyQuery && !state.isSearching &&
                    !state.isAddingSimilar ->
                    TextBodyMedium(text = stringResource(Res.string.novabird_search_no_results))

                state.isAddingSimilar -> TextBodySmall(
                    text = stringResource(Res.string.novabird_search_similar_busy),
                    color = BoltTheme.colors.onSurfaceVariant,
                )

                result != null && result.similarCount > 0 -> TextBodySmall(
                    text = stringResource(Res.string.novabird_search_similar_count, result.similarCount),
                    color = BoltTheme.colors.onSurfaceVariant,
                )
            }
            val bestCount = if (state.hasError) 0 else result?.bestCount ?: 0
            if (bestCount > 0) {
                TextBodySmall(
                    text = stringResource(Res.string.novabird_search_best_count, bestCount),
                    color = BoltTheme.colors.onSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    result?.hits.orEmpty(),
                    key = { _, hit -> "${hit.folderName}/${hit.headerMessageId}/${hit.date}" },
                ) { index, hit ->
                    HitRow(
                        hit = hit,
                        isBest = index < bestCount,
                        onClick = { hit.location?.let { onEvent(Event.OpenHit(it)) } },
                    )
                    if (index == bestCount - 1) MoreByDate() else DividerHorizontal()
                }
            }
        }
    }
}

@Composable
private fun Chip(chip: SearchChip, onRemove: () -> Unit) {
    // removing a chip repeats the search without this criterion
    ButtonOutlined(text = "${chip.label}  ✕", onClick = onRemove)
}

/** Line below the best hits: the rest of the list is ordered by date. */
@Composable
private fun MoreByDate() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(BoltTheme.colors.primary),
        )
        TextBodySmall(
            text = stringResource(Res.string.novabird_search_more_by_date),
            color = BoltTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = BoltTheme.spacings.half),
        )
        DividerHorizontal()
    }
}

/** One hit; the best ones (sort "best") get an accent on the left and a light background. */
@Composable
private fun HitRow(hit: SearchHit, isBest: Boolean, onClick: () -> Unit) {
    ClickableSurface(
        onClick = onClick,
        color = if (isBest) BoltTheme.colors.surfaceContainerLow else BoltTheme.colors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (isBest) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(BoltTheme.colors.primary),
                )
            }
            HitContent(
                hit = hit,
                modifier = Modifier.padding(start = if (isBest) BoltTheme.spacings.default else 0.dp),
            )
        }
    }
}

@Composable
private fun HitContent(hit: SearchHit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = BoltTheme.spacings.default)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextBodyMedium(
                text = hit.authorName.ifEmpty { hit.authorEmail },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextBodySmall(text = formatDate(hit.date))
        }
        val prefix = (if (hit.isRead) "" else "● ") + (if (hit.isFlagged) "★ " else "") +
            (if (hit.hasAttachment) "📎 " else "")
        TextBodyMedium(text = prefix + hit.subject, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val similar = if (hit.isSimilar) " · " + stringResource(Res.string.novabird_search_similar_label) else ""
        TextBodySmall(text = hit.folderName + similar, color = BoltTheme.colors.onSurfaceVariant)
    }
}

@OptIn(ExperimentalTime::class)
private fun formatDate(millis: Long): String {
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.day.toString().padStart(
        2,
        '0',
    )}.${date.month.ordinal.plus(1).toString().padStart(2, '0')}.${date.year}"
}
