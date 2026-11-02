package org.novabird.mail.protection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.novabird.feature.protection.ui.ProtectionSettingsScreen
import org.novabird.feature.protection.ui.ProtectionSettingsViewModel

/**
 * Settings of the phishing protection (opened from the NovaBird block at the top of the settings). Asks for the
 * contacts permission when the protection is switched on, and again on "Erlauben"; without it contacts simply do not
 * count as known.
 */
class ProtectionSettingsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val viewModel: ProtectionSettingsViewModel by viewModel()

    private val requestContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setContactsGranted(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setContactsGranted(contactsGranted())
        setContent {
            themeProvider.WithTheme {
                val state by viewModel.state.collectAsState()
                // switched on just now: ask once, right away
                LaunchedEffect(state.enabled) {
                    if (state.enabled && !contactsGranted()) requestContacts.launch(Manifest.permission.READ_CONTACTS)
                }
                ProtectionSettingsScreen(
                    viewModel = viewModel,
                    onClose = { finish() },
                    onRequestContacts = { requestContacts.launch(Manifest.permission.READ_CONTACTS) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // the user may have changed it in the system settings
        viewModel.setContactsGranted(contactsGranted())
    }

    private fun contactsGranted(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
}
