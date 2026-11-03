package app.k9mail.feature.onboarding.permissions.domain.usecase

import android.content.Context
import android.os.PowerManager
import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase

/** Whether Android lets the app run in the background without battery optimization (needed for new mail on time). */
class CheckBackgroundActivity(private val context: Context) : UseCase.CheckBackgroundActivity {
    override fun invoke(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
