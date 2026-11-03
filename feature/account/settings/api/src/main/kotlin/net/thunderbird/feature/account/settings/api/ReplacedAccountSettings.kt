package net.thunderbird.feature.account.settings.api

/**
 * Account settings an app replaces with its own (they are then left out of the account settings). Optional; without
 * it every setting is shown.
 */
fun interface ReplacedAccountSettings {
    /** The maximum size of messages to fetch is set elsewhere by the app. */
    fun isFetchMessageSizeReplaced(): Boolean
}
