package app.k9mail.feature.account.setup

import app.k9mail.feature.account.common.domain.entity.Account
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount

interface AccountSetupExternalContract {

    fun interface AccountCreator {
        suspend fun createAccount(account: Account): AccountCreatorResult

        sealed interface AccountCreatorResult {
            data class Success(val accountUuid: String) : AccountCreatorResult
            data class Error(val message: String) : AccountCreatorResult
        }
    }

    fun interface AccountOwnerNameProvider {
        suspend fun getOwnerName(): String?
    }

    /**
     * Provides the number of messages to display that is preselected for a new account.
     */
    fun interface MessageDisplayCountProvider {
        fun getDefaultMessageDisplayCount(): EmailDisplayCount
    }
}
