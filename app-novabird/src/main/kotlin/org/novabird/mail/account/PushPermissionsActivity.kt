package org.novabird.mail.account

import android.os.Bundle
import androidx.activity.compose.setContent
import app.k9mail.feature.onboarding.permissions.ui.PermissionsScreen
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject

/** The permissions page of the setup wizard on its own, shown by [PushPermissionsPrompt] after an update. */
class PushPermissionsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            themeProvider.WithTheme {
                PermissionsScreen(onNext = { finish() })
            }
        }
    }
}
