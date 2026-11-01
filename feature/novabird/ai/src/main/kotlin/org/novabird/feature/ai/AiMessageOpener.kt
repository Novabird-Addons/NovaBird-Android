package org.novabird.feature.ai

import android.app.Activity
import org.novabird.feature.search.MessageLocation

/** Opens a mail in the app's message view (bound by the app; the AI screens don't know the message view). */
fun interface AiMessageOpener {
    fun open(activity: Activity, location: MessageLocation)
}
