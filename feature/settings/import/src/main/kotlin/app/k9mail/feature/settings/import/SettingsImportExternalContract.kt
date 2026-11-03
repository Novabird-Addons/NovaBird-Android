package app.k9mail.feature.settings.import

import android.content.Context
import android.content.Intent

interface SettingsImportExternalContract {
    /**
     * Activate account after server password(s) have been provided on settings import.
     */
    interface AccountActivator {
        fun enableAccount(accountUuid: String, incomingServerPassword: String?, outgoingServerPassword: String?)

        fun enableAccount(accountUuid: String)
    }

    /**
     * Optional: a screen of the app shown for the imported accounts before the import screen closes, e.g. to ask how
     * many messages to sync (imported accounts skip the setup wizard).
     */
    fun interface ImportedAccountsReview {
        fun createIntent(context: Context, accountUuids: List<String>): Intent
    }
}
