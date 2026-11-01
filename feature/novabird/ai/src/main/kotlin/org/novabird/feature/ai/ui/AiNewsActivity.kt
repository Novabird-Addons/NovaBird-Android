package org.novabird.feature.ai.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.components.ui.bolt.atom.CircularProgressIndicator
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.novabird.feature.ai.AiMessageOpener
import org.novabird.feature.ai.R
import org.novabird.feature.ai.assist.Overview
import org.novabird.feature.ai.settings.AiSettings
import org.novabird.feature.ai.speech.SpeechPlayer
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.vectors.ai.Day

/** "Was gibt's Neues?" – opened from the main menu. */
class AiNewsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val opener: AiMessageOpener by inject()
    private val viewModel: AiNewsViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) viewModel.run()
        setContent {
            themeProvider.WithTheme {
                val state by viewModel.state.collectAsState()
                val speech by viewModel.speech.state.collectAsState()
                AiNewsScreen(
                    state = state,
                    speech = speech,
                    actions = NewsActions(
                        setHours = viewModel::setHours,
                        setUnreadOnly = viewModel::setUnreadOnly,
                        refresh = viewModel::run,
                        read = viewModel::read,
                        stopReading = viewModel::stopReading,
                        open = { opener.open(this, it) },
                    ),
                    onBack = { finish() },
                )
            }
        }
    }
}

internal class NewsActions(
    val setHours: (Int) -> Unit,
    val setUnreadOnly: (Boolean) -> Unit,
    val refresh: () -> Unit,
    val read: () -> Unit,
    val stopReading: () -> Unit,
    val open: (MessageLocation) -> Unit,
)

@Composable
private fun AiNewsScreen(state: AiNewsUiState, speech: SpeechPlayer.State, actions: NewsActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBarWithBackButton(title = stringResource(R.string.novabird_ai_news_title), onBackClick = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
        ) {
            if (!state.available) {
                TextBodyMedium(text = stringResource(R.string.novabird_ai_needs_helper))
                return@Column
            }
            Choices(state, actions)
            if (state.running) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    TextBodySmall(
                        text = if (state.all > 0) {
                            stringResource(R.string.novabird_ai_news_progress, state.done, state.all)
                        } else {
                            stringResource(R.string.novabird_ai_news_loading)
                        },
                    )
                }
            }
            state.error?.let { TextBodySmall(text = aiErrorText(it), color = BoltTheme.colors.error) }
            state.overview?.let { overview ->
                if (!state.running) SpeechButtons(speech, onRead = actions.read, onStop = actions.stopReading)
                OverviewList(overview, actions.open)
            }
        }
    }
}

@Composable
private fun Choices(state: AiNewsUiState, actions: NewsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        val labels = mapOf(
            12 to R.string.novabird_ai_news_12h,
            24 to R.string.novabird_ai_news_24h,
            72 to R.string.novabird_ai_news_3d,
            168 to R.string.novabird_ai_news_7d,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for (h in AiSettings.NEWS_HOURS) {
                val text = stringResource(labels.getValue(h))
                if (h == state.hours) {
                    ButtonFilled(text = text, onClick = { actions.refresh() })
                } else {
                    ButtonOutlined(text = text, onClick = { actions.setHours(h) })
                }
            }
        }
        SwitchRow(
            title = stringResource(R.string.novabird_ai_news_unread),
            checked = state.unreadOnly,
            onChange = actions.setUnreadOnly,
        )
    }
}

@Composable
private fun OverviewList(overview: Overview, open: (MessageLocation) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        if (overview.mails.isEmpty()) {
            TextBodyMedium(text = stringResource(R.string.novabird_ai_news_none))
            return@Column
        }
        if (overview.total > overview.mails.size) {
            TextBodySmall(
                text = stringResource(R.string.novabird_ai_news_more, overview.mails.size, overview.total),
                color = BoltTheme.colors.onSurfaceVariant,
            )
        }
        if (overview.suspicious) {
            TextBodySmall(text = stringResource(R.string.novabird_ai_news_suspicious), color = BoltTheme.colors.error)
        }
        val sections = listOf(
            "todo" to R.string.novabird_ai_news_todo,
            "info" to R.string.novabird_ai_news_info,
            "ads" to R.string.novabird_ai_news_ads,
        )
        for ((cat, title) in sections) {
            val rows = overview.entries.withIndex().filter { it.value.cat == cat }
            if (rows.isEmpty()) continue
            TextTitleMedium(text = stringResource(title))
            for ((i, e) in rows) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open(overview.mails[i].location) }
                        .padding(vertical = BoltTheme.spacings.half),
                ) {
                    TextBodyLarge(text = if (e.who.isNotEmpty()) "${e.who}: ${e.what}" else e.what)
                    if (cat == "todo") {
                        for (t in e.tasks) {
                            val due = Day.parse(t.due)?.let {
                                " – " +
                                    stringResource(R.string.novabird_ai_news_due, "${it.day}.${it.month}.")
                            }
                            TextBodySmall(text = "• ${t.title}${due.orEmpty()}", color = BoltTheme.colors.primary)
                        }
                    }
                    TextBodySmall(text = overview.mails[i].subject, color = BoltTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}
