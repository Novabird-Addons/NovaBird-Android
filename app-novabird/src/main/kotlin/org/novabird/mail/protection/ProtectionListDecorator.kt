package org.novabird.mail.protection

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.fsck.k9.ui.MessageListItemDecorator
import com.fsck.k9.ui.messagelist.MessageListItem
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.novabird.feature.protection.ListMark
import org.novabird.feature.protection.ProtectionLevel
import org.novabird.feature.protection.ProtectionService
import org.novabird.mail.R

/**
 * Warning symbol in the message list (`nb_protection_badge` in the layout overlay `message_list_item.xml`) for mails
 * rated "Vorsicht" or "gefährlich", grey for spam; nothing for unchecked or normal mails (no green check).
 *
 * Binding is synchronous, so levels come from a cache; misses are loaded in small batches and set on the row if it
 * still shows the same message. Whenever the stored results change (a background check finished, a sender was
 * trusted), the cache is dropped and the rows on screen are looked up again, so badges appear without a scroll.
 *
 * @param scope main-thread scope of the app.
 */
class ProtectionListDecorator(
    private val service: ProtectionService,
    private val scope: CoroutineScope,
) : MessageListItemDecorator {

    private data class Key(val accountUuid: String, val k9Id: Long)

    private val cache = HashMap<Key, ListMark?>()
    private val pending = LinkedHashMap<Key, MutableList<WeakReference<ImageView>>>()
    private var loader: Job? = null

    /** Badges bound so far (rows on screen or recycled), with the message they show. */
    private val bound = WeakHashMap<ImageView, Key>()

    init {
        scope.launch {
            service.revision.collect {
                cache.clear()
                for ((badge, key) in bound.entries.toList()) {
                    if (badge.tag == key) enqueue(key, badge)
                }
            }
        }
    }

    override fun decorate(itemView: View, item: MessageListItem) {
        val badge = itemView.findViewById<ImageView>(R.id.nb_protection_badge) ?: return
        val key = Key(item.account.uuid, item.databaseId)
        badge.tag = key
        bound[badge] = key
        when {
            !service.isEnabled -> badge.isVisible = false

            cache.containsKey(key) -> apply(badge, cache[key])

            else -> {
                badge.isVisible = false
                enqueue(key, badge)
            }
        }
    }

    private fun enqueue(key: Key, badge: ImageView) {
        pending.getOrPut(key) { mutableListOf() } += WeakReference(badge)
        if (loader == null) loader = scope.launch { loadPending() }
    }

    private suspend fun loadPending() {
        delay(BATCH_DELAY_MS)
        val batch = pending.toMap()
        pending.clear()
        loader = null
        for ((accountUuid, keys) in batch.keys.groupBy { it.accountUuid }) {
            val marks = runCatching { service.marks(accountUuid, keys.map { it.k9Id }) }.getOrDefault(emptyMap())
            for (key in keys) {
                val mark = marks[key.k9Id]
                cache[key] = mark
                for (ref in batch.getValue(key)) {
                    val badge = ref.get() ?: continue
                    if (badge.tag == key) apply(badge, mark)
                }
            }
        }
    }

    private fun apply(badge: ImageView, mark: ListMark?) {
        when {
            mark?.level == ProtectionLevel.DANGEROUS -> show(badge, R.color.nb_status_danger)
            mark?.level == ProtectionLevel.SUSPICIOUS -> show(badge, R.color.nb_status_warn)
            mark?.spam == true -> show(badge, R.color.nb_status_spam)
            else -> badge.isVisible = false
        }
    }

    private fun show(badge: ImageView, color: Int) {
        badge.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(badge.context, color))
        badge.isVisible = true
    }

    private companion object {
        const val BATCH_DELAY_MS = 40L
    }
}
