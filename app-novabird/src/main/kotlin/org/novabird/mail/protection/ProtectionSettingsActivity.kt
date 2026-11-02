package org.novabird.mail.protection

import android.os.Bundle
import androidx.activity.compose.setContent
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.novabird.feature.protection.ui.ProtectionSettingsScreen
import org.novabird.feature.protection.ui.ProtectionSettingsViewModel

/** Settings of the phishing protection (opened from the NovaBird block at the top of the settings). */
class ProtectionSettingsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val viewModel: ProtectionSettingsViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            themeProvider.WithTheme {
                ProtectionSettingsScreen(viewModel = viewModel, onClose = { finish() })
            }
        }
    }
}
