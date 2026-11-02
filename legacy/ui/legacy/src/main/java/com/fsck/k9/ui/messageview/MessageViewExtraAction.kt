package com.fsck.k9.ui.messageview

import android.app.Activity
import androidx.annotation.StringRes
import app.k9mail.legacy.message.controller.MessageReference

/**
 * NovaBird: an extra entry in the overflow menu of the message view (e.g. AI summary, read aloud). Apps bind any number
 * with Koin; the message view shows them after its own entries.
 */
class MessageViewExtraAction(
    @StringRes val label: Int,
    val onClick: (Activity, MessageReference) -> Unit,
)
