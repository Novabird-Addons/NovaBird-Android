package org.novabird.mail.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.novabird.feature.sync.fullsync.FullSyncActivity
import org.novabird.feature.sync.fullsync.FullSyncProgress
import org.novabird.feature.sync.fullsync.FullSyncScheduler
import org.novabird.feature.sync.fullsync.FullSyncState
import org.novabird.feature.sync.R as SyncR

/**
 * A thin bar above the message list while an account loads all its mail ("Jetzt alle Mails abrufen"), so the progress
 * is visible in the app, not only in the notification (which the user may have switched off). A tap opens the page of
 * the full sync. Placed by the layout overlay message_list_fragment.xml.
 */
class FullSyncProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), KoinComponent {

    private val scheduler: FullSyncScheduler by inject()
    private val accountManager: LegacyAccountDtoManager by inject()

    private val label = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val bar = LinearProgressIndicator(context).apply { max = BAR_MAX }
    private var job: Job? = null

    init {
        orientation = VERTICAL
        isVisible = false
        setPadding(dp(PADDING_HORIZONTAL), dp(PADDING_VERTICAL), dp(PADDING_HORIZONTAL), dp(PADDING_VERTICAL))
        addView(label)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner() ?: return
        job = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val uuids = accountManager.getAccounts().map { it.uuid }
                if (uuids.isEmpty()) {
                    isVisible = false
                    return@repeatOnLifecycle
                }
                combine(uuids.map { uuid -> scheduler.state(uuid).map { uuid to it } }) { it.toList() }
                    .collect(::show)
            }
        }
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        job = null
        super.onDetachedFromWindow()
    }

    private fun show(states: List<Pair<String, FullSyncState>>) {
        val (uuid, state) = states.firstOrNull { it.second is FullSyncState.Running }
            ?: states.firstOrNull { it.second is FullSyncState.Waiting }
            ?: run {
                isVisible = false
                return
            }
        isVisible = true
        setOnClickListener { context.startActivity(FullSyncActivity.intent(context, uuid)) }
        val progress = (state as? FullSyncState.Running)?.progress
        label.text = when {
            progress == null -> context.getString(SyncR.string.novabird_sync_full_waiting)

            progress.folderIndex == 0 -> context.getString(SyncR.string.novabird_sync_full_preparing)

            progress.total > 0 -> context.getString(
                SyncR.string.novabird_sync_full_progress_messages,
                progress.folderIndex,
                progress.folderCount,
                progress.folderName,
                progress.done,
                progress.total,
            )

            else -> context.getString(
                SyncR.string.novabird_sync_full_progress_folder,
                progress.folderIndex,
                progress.folderCount,
                progress.folderName,
            )
        }
        bar.setProgressCompat(progress?.let(::overall) ?: 0, true)
    }

    // the folders count equally, the current one by its messages
    private fun overall(progress: FullSyncProgress): Int {
        if (progress.folderCount == 0) return 0
        val current = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
        val folders = (progress.folderIndex - 1).coerceAtLeast(0) + current.coerceIn(0f, 1f)
        return (folders / progress.folderCount * BAR_MAX).toInt()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BAR_MAX = 1000
        const val TEXT_SP = 13f
        const val PADDING_HORIZONTAL = 16
        const val PADDING_VERTICAL = 4
    }
}
