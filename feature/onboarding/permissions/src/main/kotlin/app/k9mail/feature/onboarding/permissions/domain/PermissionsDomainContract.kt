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

        /** Optional (bound by the app): true if the app may set exact alarms, which push needs (Android 12 and up). */
        fun interface CheckExactAlarms {
            operator fun invoke(): Boolean
        }

        /** True if notifications or exact alarms are missing, so new mail would not come right away (NovaBird). */
        fun interface CheckPushPermissionsMissing {
            operator fun invoke(): Boolean
        }
    }
}
