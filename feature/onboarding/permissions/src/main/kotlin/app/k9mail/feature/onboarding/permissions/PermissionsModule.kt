package app.k9mail.feature.onboarding.permissions

import app.k9mail.core.android.permissions.corePermissionsAndroidModule
import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase
import app.k9mail.feature.onboarding.permissions.domain.usecase.CheckPermission
import app.k9mail.feature.onboarding.permissions.domain.usecase.CheckPushPermissionsMissing
import app.k9mail.feature.onboarding.permissions.ui.PermissionsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val featureOnboardingPermissionsModule: Module = module {
    includes(corePermissionsAndroidModule)

    factory<UseCase.CheckPermission> { CheckPermission(permissionChecker = get()) }
    factory<UseCase.CheckPushPermissionsMissing> {
        CheckPushPermissionsMissing(checkPermission = get(), checkExactAlarms = getOrNull())
    }

    viewModel {
        PermissionsViewModel(
            checkPermission = get(),
            checkBackgroundActivity = getOrNull(),
            checkExactAlarms = getOrNull(),
        )
    }
}
