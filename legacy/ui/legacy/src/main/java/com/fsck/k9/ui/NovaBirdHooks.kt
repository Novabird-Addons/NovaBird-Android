package com.fsck.k9.ui

import android.net.Uri
import android.view.View
import android.webkit.WebView
import com.fsck.k9.mailstore.MessageViewInfo
import com.fsck.k9.ui.messagelist.MessageListItem
import com.fsck.k9.ui.messageview.MessageTopView
import net.thunderbird.core.android.account.LegacyAccountDto

// NovaBird: optional hooks for the message view and list. Bound by the app with Koin; absent in Thunderbird.

/** Called after a message was rendered in the message view (also again after a theme or plain-text switch). */
fun interface MessageViewHook {
    fun onMessageShown(messageTopView: MessageTopView, account: LegacyAccountDto, messageViewInfo: MessageViewInfo)
}

/** Called after a message list row was bound; may read or set views of the row layout that the app adds. */
fun interface MessageListItemDecorator {
    fun decorate(itemView: View, item: MessageListItem)
}

/**
 * Asked before a link in a message is opened (tap in the web view, "open link" in its context menu); `true` means the
 * gate took over (e.g. shows a dialog). [view] is a view inside the message view.
 */
fun interface MessageLinkGate {
    fun shouldIntercept(view: View, uri: Uri): Boolean
}
