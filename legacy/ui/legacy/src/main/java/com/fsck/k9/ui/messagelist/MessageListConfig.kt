package com.fsck.k9.ui.messagelist

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.core.android.account.SortType
import net.thunderbird.feature.search.legacy.LocalMessageSearch

data class MessageListConfig(
    val search: LocalMessageSearch,
    val showingThreadedList: Boolean,
    val sortType: SortType,
    val sortAscending: Boolean,
    val sortDateAscending: Boolean,
    val activeMessage: MessageReference?,
    val sortOverrides: Map<MessageReference, MessageSortOverride>,
    /** The first messages to load, 0 for all (NovaBird): rows cost time and memory, 45,000 mails 16 s and 60 MB. */
    val limit: Int = 0,
)

data class MessageSortOverride(
    val isRead: Boolean,
    val isStarred: Boolean,
)
