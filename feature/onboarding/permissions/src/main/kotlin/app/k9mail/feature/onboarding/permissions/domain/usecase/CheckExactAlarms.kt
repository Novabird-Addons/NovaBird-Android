package app.k9mail.feature.onboarding.permissions.domain.usecase

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase

/**
 * Whether the app may set exact alarms. Push keeps the connection to the server alive with them; since Android 14 an
 * app doesn't get them on its own, and without them push stays off (NovaBird).
 */
class CheckExactAlarms(private val context: Context) : UseCase.CheckExactAlarms {
    override fun invoke(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false
}
