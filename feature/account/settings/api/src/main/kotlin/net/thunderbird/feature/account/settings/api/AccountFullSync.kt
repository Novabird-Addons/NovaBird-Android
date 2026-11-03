package net.thunderbird.feature.account.settings.api

import android.content.Context
import android.content.Intent

/**
 * Optional: the app's screen to load all messages of an account at once, offered in the account settings under
 * "Fetching mail". Without it the entry is not shown.
 */
fun interface AccountFullSync {
    fun createIntent(context: Context, accountUuid: String): Intent
}
