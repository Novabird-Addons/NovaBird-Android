package org.novabird.feature.sync.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Settings screen for the NovaBird synchronization limits.
 */
class SyncLimitSettingsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val viewModel: SyncLimitSettingsViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            themeProvider.WithTheme {
                val state by viewModel.state.collectAsState()

                SyncLimitSettingsScreen(state = state, onEvent = viewModel::event, onBack = ::finish)
            }
        }
    }
}
