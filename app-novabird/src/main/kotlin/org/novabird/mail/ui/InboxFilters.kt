package org.novabird.mail.ui

import androidx.annotation.StringRes
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition
import net.thunderbird.feature.search.legacy.serialization.LocalMessageSearchSerializer
import org.novabird.mail.R

/** Quick filters above the message list; each one narrows the list's search by one condition. */
internal enum class InboxFilter(@param:StringRes val label: Int, val condition: SearchCondition) {
    UNREAD(R.string.novabird_filter_unread, SearchCondition(MessageSearchField.READ, SearchAttribute.EQUALS, "0")),
    STARRED(R.string.novabird_filter_starred, SearchCondition(MessageSearchField.FLAGGED, SearchAttribute.EQUALS, "1")),
    ATTACHMENT(
        R.string.novabird_filter_attachment,
        SearchCondition(MessageSearchField.ATTACHMENT_COUNT, SearchAttribute.NOT_EQUALS, "0"),
    ),
}

internal object InboxFilters {
    /** The unfiltered search of the last list a filter was chosen in, to go back to with "All". */
    @Volatile
    var lastBase: ByteArray? = null
        private set

    /** The filter a list's search was narrowed by, or null for an unfiltered list. */
    fun activeFilter(search: LocalMessageSearch): InboxFilter? {
        val conditions = search.leafSet.mapNotNull { it.condition }
        return InboxFilter.entries.firstOrNull { it.condition in conditions }
    }

    /** The search of the list [current] narrowed by [filter]; remembers the unfiltered search for "All". */
    fun filtered(current: ByteArray, filter: InboxFilter): LocalMessageSearch {
        val currentSearch = LocalMessageSearchSerializer.deserialize(current)
        val base = if (activeFilter(currentSearch) == null) current else lastBase ?: current
        lastBase = base
        return LocalMessageSearchSerializer.deserialize(base).apply { and(filter.condition) }
    }
}
