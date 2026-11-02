package org.novabird.mail.ai

import android.content.Intent
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.ui.messageview.MessageViewExtraAction
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.novabird.feature.ai.AiMessageOpener
import org.novabird.feature.ai.R
import org.novabird.feature.ai.ui.AiMailActivity
import org.novabird.feature.search.MessageLocation

/** AI entries in the message view's menu (summary, read aloud) and how the AI screens open a mail. */
val novaBirdAiAppModule = module {
    single(named("novabird_ai_summary")) {
        MessageViewExtraAction(R.string.novabird_ai_menu_summary) { activity, ref ->
            activity.startActivity(AiMailActivity.intent(activity, ref.toLocation(), AiMailActivity.MODE_SUMMARY))
        }
    }
    single(named("novabird_ai_read")) {
        MessageViewExtraAction(R.string.novabird_ai_menu_read) { activity, ref ->
            activity.startActivity(AiMailActivity.intent(activity, ref.toLocation(), AiMailActivity.MODE_READ))
        }
    }
    single {
        AiMessageOpener { activity, location ->
            val reference = MessageReference(location.accountUuid, location.folderId, location.uid)
            // only the message on top of the overview, so "back" returns to it
            val intent = MessageHomeActivity.actionDisplayMessageIntent(
                context = activity,
                messageReference = reference,
                messageViewOnly = true,
            ).apply { flags = flags and Intent.FLAG_ACTIVITY_CLEAR_TOP.inv() }
            activity.startActivity(intent)
        }
    }
}

private fun MessageReference.toLocation() = MessageLocation(accountUuid, folderId, uid)
