package org.novabird.mail.search

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.internal.ui.NovaBirdSearchScreen

/**
 * Replaces the K-9 search results screen: the manifest maps `com.fsck.k9.activity.MessageSearchActivity` to this
 * activity (activity-alias), so the search field of the message list opens the NovaBird search without changes to
 * upstream code. The typed query arrives as [SearchManager.QUERY].
 */
class NovaBirdSearchActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialQuery = intent.getStringExtra(SearchManager.QUERY)

        setContent {
            themeProvider.WithTheme {
                NovaBirdSearchScreen(
                    onOpenMessage = ::openMessage,
                    onBack = ::finish,
                    initialQuery = initialQuery,
                )
            }
        }
    }

    private fun openMessage(location: MessageLocation) {
        val messageReference = MessageReference(
            accountUuid = location.accountUuid,
            folderId = location.folderId,
            uid = location.uid,
        )

        // Show only the message on top of the search, so "back" returns to the search results.
        val intent = MessageHomeActivity.actionDisplayMessageIntent(
            context = this,
            messageReference = messageReference,
            messageViewOnly = true,
        ).apply {
            flags = flags and Intent.FLAG_ACTIVITY_CLEAR_TOP.inv()
        }
        startActivity(intent)
    }
}
