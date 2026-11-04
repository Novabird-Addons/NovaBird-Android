package com.fsck.k9.controller

import app.k9mail.legacy.message.controller.MessageReference

// NovaBird: optional hook, bound by the app with Koin; absent in Thunderbird.

/**
 * Called right before the user moves [messages] to the spam folder by hand (list, swipe, message view, notification),
 * while they are still in their old folder. Not called for moves by the app itself.
 */
fun interface ManualSpamHook {
    fun onMarkedAsSpam(messages: List<MessageReference>)
}
