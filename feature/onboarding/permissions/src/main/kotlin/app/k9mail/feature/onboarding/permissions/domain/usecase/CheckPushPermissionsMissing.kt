package app.k9mail.feature.onboarding.permissions.domain.usecase

import app.k9mail.core.android.permissions.Permission
import app.k9mail.core.android.permissions.PermissionState
import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase

/** Notifications or exact alarms are missing: a new account then shows the permissions page too (NovaBird). */
class CheckPushPermissionsMissing(
    private val checkPermission: UseCase.CheckPermission,
    private val checkExactAlarms: UseCase.CheckExactAlarms?,
) : UseCase.CheckPushPermissionsMissing {
    override fun invoke(): Boolean =
        checkPermission(Permission.Notifications) == PermissionState.Denied || checkExactAlarms?.invoke() == false
}
