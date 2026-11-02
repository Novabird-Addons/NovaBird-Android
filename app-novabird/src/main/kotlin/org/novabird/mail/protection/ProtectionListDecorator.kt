package org.novabird.mail.protection

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.fsck.k9.ui.MessageListItemDecorator
import com.fsck.k9.ui.messagelist.MessageListItem
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.novabird.feature.protection.ProtectionLevel
import org.novabird.feature.protection.ProtectionService
import org.novabird.mail.R

/**
 * Warning symbol in the message list (`nb_protection_badge` in the layout overlay `message_list_item.xml`) for mails
 * rated "Vorsicht" or "gefährlich"; nothing for unchecked or normal mails (no green check).
 *
 * Binding is synchronous, so levels come from a cache; misses are loaded in small batches and set on the row if it
 * still shows the same message. The cache is dropped whenever the stored results change.
 *
 * @param scope main-thread scope of the app.
 */
class ProtectionListDecorator(
    private val service: ProtectionService,
    private val scope: CoroutineScope,
) : MessageListItemDecorator {

    private data class Key(val accountUuid: String, val k9Id: Long)

    private val cache = HashMap<Key, ProtectionLevel?>()
    private val pending = LinkedHashMap<Key, MutableList<WeakReference<ImageView>>>()
    private var loader: Job? = null

    init {
        scope.launch { service.revision.collect { cache.clear() } }
    }

    override fun decorate(itemView: View, item: MessageListItem) {
        val badge = itemView.findViewById<ImageView>(R.id.nb_protection_badge) ?: return
        val key = Key(item.account.uuid, item.databaseId)
        badge.tag = key
        when {
            !service.isEnabled -> badge.isVisible = false

            cache.containsKey(key) -> apply(badge, cache[key])

            else -> {
                badge.isVisible = false
                pending.getOrPut(key) { mutableListOf() } += WeakReference(badge)
                if (loader == null) loader = scope.launch { loadPending() }
            }
        }
    }

    private suspend fun loadPending() {
        delay(BATCH_DELAY_MS)
        val batch = pending.toMap()
        pending.clear()
        loader = null
        for ((accountUuid, keys) in batch.keys.groupBy { it.accountUuid }) {
            val levels = runCatching { service.levels(accountUuid, keys.map { it.k9Id }) }.getOrDefault(emptyMap())
            for (key in keys) {
                val level = levels[key.k9Id]
                cache[key] = level
                for (ref in batch.getValue(key)) {
                    val badge = ref.get() ?: continue
                    if (badge.tag == key) apply(badge, level)
                }
            }
        }
    }

    private fun apply(badge: ImageView, level: ProtectionLevel?) {
        when (level) {
            ProtectionLevel.SUSPICIOUS -> show(badge, R.color.nb_status_warn)
            ProtectionLevel.DANGEROUS -> show(badge, R.color.nb_status_danger)
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
