package net.thunderbird.app.common.account

// NovaBird: optional hook, bound by the app with Koin; absent in Thunderbird.

/** Called after the setup wizard created an account and loaded its folder list. */
fun interface NewAccountHook {
    fun onAccountCreated(accountUuid: String)
}
