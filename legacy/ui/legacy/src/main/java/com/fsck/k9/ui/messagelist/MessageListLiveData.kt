package com.fsck.k9.ui.messagelist

import androidx.lifecycle.LiveData
import app.k9mail.legacy.mailstore.MessageListChangedListener
import app.k9mail.legacy.mailstore.MessageListRepository
import com.fsck.k9.search.getLegacyAccountUuids
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountManager

private val MIN_RELOAD_PAUSE = 200.milliseconds

class MessageListLiveData(
    private val messageListLoader: MessageListLoader,
    private val accountManager: LegacyAccountManager,
    private val messageListRepository: MessageListRepository,
    private val coroutineScope: CoroutineScope,
    val config: MessageListConfig,
) : LiveData<MessageListInfo>() {

    private val reloads = Channel<Unit>(Channel.CONFLATED)
    private var reloadJob: Job? = null

    private val messageListChangedListener = MessageListChangedListener {
        reloads.trySend(Unit)
    }

    /**
     * One load at a time, and changes in the meantime count as one (NovaBird). A sync reports every single message:
     * with a load for each, tens of them ran side by side, each with the whole list in memory (out of memory with
     * 10,000 mails), and the sync got a thirtieth of its speed. The pause leaves the database to the sync for as
     * long as the last load took.
     */
    private suspend fun reloadOnChanges() {
        for (change in reloads) {
            val start = TimeSource.Monotonic.markNow()
            val messageList = withContext(Dispatchers.IO) {
                messageListLoader.getMessageList(config)
            }
            value = messageList
            delay(start.elapsedNow().coerceAtLeast(MIN_RELOAD_PAUSE))
        }
    }

    override fun onActive() {
        super.onActive()

        registerMessageListChangedListenerAsync()
        reloads.trySend(Unit)
        reloadJob = coroutineScope.launch(Dispatchers.Main) { reloadOnChanges() }
    }

    override fun onInactive() {
        super.onInactive()
        messageListRepository.removeListener(messageListChangedListener)
        reloadJob?.cancel()
    }

    private fun registerMessageListChangedListenerAsync() {
        coroutineScope.launch(Dispatchers.IO) {
            val accountUuids = config.search.getLegacyAccountUuids(accountManager)

            for (accountUuid in accountUuids) {
                messageListRepository.addListener(accountUuid, messageListChangedListener)
            }
        }
    }
}
