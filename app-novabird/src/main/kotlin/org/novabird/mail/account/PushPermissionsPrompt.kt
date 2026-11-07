package org.novabird.mail.account

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase.CheckPushPermissionsMissing
import com.fsck.k9.activity.MessageHomeActivity
import java.util.concurrent.Executors
import net.thunderbird.core.android.account.LegacyAccountDtoManager

/**
 * Once per app version: if notifications or exact alarms are missing (push then stays off), the first time the message
 * list opens shows the permissions page of the setup wizard (user decision 2026-11-07). A new install leaves this to
 * the wizard and only remembers the version.
 */
class PushPermissionsPrompt(
    private val context: Context,
    private val versionCode: Int,
    private val accountManager: LegacyAccountDtoManager,
    private val pushPermissionsMissing: CheckPushPermissionsMissing,
) : Application.ActivityLifecycleCallbacks {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var checked = false

    init {
        // a new install (no account yet at app start) gets the page in the wizard
        executor.execute {
            if (accountManager.getAccounts().isEmpty()) {
                preferences.edit().putInt(KEY_ASKED_VERSION, versionCode).apply()
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (checked || activity !is MessageHomeActivity) return
        checked = true
        executor.execute {
            if (preferences.getInt(KEY_ASKED_VERSION, 0) >= versionCode) return@execute
            preferences.edit().putInt(KEY_ASKED_VERSION, versionCode).apply()
            if (accountManager.getAccounts().isNotEmpty() && pushPermissionsMissing()) {
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        activity.startActivity(
                            Intent(activity, PushPermissionsActivity::class.java),
                        )
                    }
                }
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val PREFERENCES = "novabird_permissions"
        const val KEY_ASKED_VERSION = "askedVersion"
    }
}
