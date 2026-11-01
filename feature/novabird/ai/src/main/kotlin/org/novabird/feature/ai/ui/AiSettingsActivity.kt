package org.novabird.feature.ai.ui

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Settings for the semantic search and AI functions: NovaBird Helper (recommended, GPU on the PC) and computing on
 * the phone (model download, keep loaded, threads). Read-aloud and summary are shown greyed until they exist.
 */
class AiSettingsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val viewModel: AiSettingsViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            themeProvider.WithTheme {
                val state by viewModel.state.collectAsState()
                AiSettingsScreen(
                    state = state,
                    actions = viewModel,
                    isMetered = ::isMetered,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkHelper()
    }

    private fun isMetered(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }
}
