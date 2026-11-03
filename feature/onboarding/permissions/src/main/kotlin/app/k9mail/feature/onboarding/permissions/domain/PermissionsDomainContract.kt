package app.k9mail.feature.onboarding.permissions.domain

import app.k9mail.core.android.permissions.Permission
import app.k9mail.core.android.permissions.PermissionState

interface PermissionsDomainContract {

    interface UseCase {

        fun interface CheckPermission {
            operator fun invoke(permission: Permission): PermissionState
        }

        /** Optional (bound by the app): true if the app may run in the background without battery optimization. */
        fun interface CheckBackgroundActivity {
            operator fun invoke(): Boolean
        }
    }
}
