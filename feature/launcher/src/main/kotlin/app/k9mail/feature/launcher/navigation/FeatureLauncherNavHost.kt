package app.k9mail.feature.launcher.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.k9mail.feature.account.edit.navigation.AccountEditNavigation
import app.k9mail.feature.account.setup.navigation.AccountSetupNavigation
import app.k9mail.feature.account.setup.navigation.AccountSetupRoute
import app.k9mail.feature.launcher.FeatureLauncherExternalContract.MessageListLauncher
import app.k9mail.feature.onboarding.main.navigation.OnboardingNavigation
import app.k9mail.feature.onboarding.main.navigation.OnboardingRoute
import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase.CheckPushPermissionsMissing
import app.k9mail.feature.onboarding.permissions.ui.PermissionsScreen
import net.thunderbird.feature.account.settings.api.AccountSettingsNavigation
import net.thunderbird.feature.debug.settings.navigation.SecretDebugSettingsNavigation
import net.thunderbird.feature.debug.settings.navigation.SecretDebugSettingsRoute
import net.thunderbird.feature.funding.api.FundingNavigation
import net.thunderbird.feature.navigation.changelog.api.ChangelogNavigation
import net.thunderbird.feature.thundermail.navigation.ThundermailNavigation
import net.thunderbird.feature.thundermail.navigation.ThundermailRoute
import org.koin.compose.koinInject

@Suppress("LongMethod")
@Composable
fun FeatureLauncherNavHost(
    navController: NavHostController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    messageListLauncher: MessageListLauncher = koinInject(),
    accountEditNavigation: AccountEditNavigation = koinInject(),
    accountSettingsNavigation: AccountSettingsNavigation = koinInject(),
    accountSetupNavigation: AccountSetupNavigation = koinInject(),
    onboardingNavigation: OnboardingNavigation = koinInject(),
    fundingNavigation: FundingNavigation = koinInject(),
    secretDebugSettingsNavigation: SecretDebugSettingsNavigation = koinInject(),
    thundermailNavigation: ThundermailNavigation = koinInject(),
    changelogNavigation: ChangelogNavigation = koinInject(),
    pushPermissionsMissing: CheckPushPermissionsMissing = koinInject(),
) {
    val activity = LocalActivity.current as ComponentActivity
    // NovaBird: the account that waits for the permissions page
    var accountAfterPermissions by rememberSaveable { mutableStateOf<String?>(null) }

    NavHost(
        navController = navController,
        startDestination = OnboardingRoute.Onboarding(),
        modifier = modifier,
    ) {
        changelogNavigation.registerRoutes(
            navGraphBuilder = this,
            onBack = onBack,
            onFinish = { onBack() },
        )
        onboardingNavigation.registerRoutes(
            navGraphBuilder = this,
            onBack = onBack,
            onFinish = {
                when (it) {
                    is OnboardingRoute.Onboarding -> {
                        messageListLauncher.launch(it.accountId)
                        activity.finish()
                    }

                    is OnboardingRoute.ThundermailScanQrCode ->
                        navController.navigate(ThundermailRoute.ScanQrCode)

                    is OnboardingRoute.ThundermailSignIn ->
                        navController.navigate(ThundermailRoute.SignInWithThundermail)

                    is OnboardingRoute.ThundermailAddAccount ->
                        navController.navigate(ThundermailRoute.AddAccount)
                }
            },
        )

        registerThundermailNavigation(
            thundermailNavigation = thundermailNavigation,
            navController = navController,
            messageListLauncher = messageListLauncher,
            activity = activity,
        )

        accountSetupNavigation.registerRoutes(
            navGraphBuilder = this,
            onBack = onBack,
            onFinish = {
                when (it) {
                    // NovaBird: a further account also asks for what push needs, if it's missing (only the first
                    // account went through onboarding's permissions page)
                    is AccountSetupRoute.AccountSetup -> if (pushPermissionsMissing()) {
                        accountAfterPermissions = it.accountId
                        navController.navigate(NOVABIRD_PERMISSIONS_ROUTE)
                    } else {
                        messageListLauncher.launch(it.accountId)
                    }

                    is AccountSetupRoute.ThundermailScanQrCode ->
                        navController.navigate(ThundermailRoute.ScanQrCode)

                    is AccountSetupRoute.ThundermailSignIn ->
                        navController.navigate(ThundermailRoute.SignInWithThundermail)
                }
            },
        )

        composable(route = NOVABIRD_PERMISSIONS_ROUTE) {
            PermissionsScreen(onNext = { messageListLauncher.launch(accountAfterPermissions) })
        }

        accountEditNavigation.registerRoutes(
            navGraphBuilder = this,
            onBack = onBack,
            onFinish = { activity.finish() },
        )

        accountSettingsNavigation.registerRoutes(
            navGraphBuilder = this,
            onBack = onBack,
            onFinish = {
                onBack()
            },
        )

        fundingNavigation.registerRoutes(
            navGraphBuilder = this,
            onBack = onBack,
            onFinish = { onBack() },
        )

        secretDebugSettingsNavigation.registerRoutes(
            navGraphBuilder = this,
            onBack = onBack,
            onFinish = { route ->
                when (route.tab) {
                    SecretDebugSettingsRoute.Tab.Notification -> onBack()
                    SecretDebugSettingsRoute.Tab.FeatureFlag -> messageListLauncher.launch(accountUuid = null)
                }
            },
        )
    }
}

private const val NOVABIRD_PERMISSIONS_ROUTE = "novabird_permissions"

private fun NavGraphBuilder.registerThundermailNavigation(
    thundermailNavigation: ThundermailNavigation,
    navController: NavHostController,
    messageListLauncher: MessageListLauncher,
    activity: ComponentActivity,
) {
    thundermailNavigation.registerRoutes(
        navGraphBuilder = this,
        onBack = { navController.popBackStack() },
        onFinish = { route ->
            when (route) {
                is ThundermailRoute.IncomingSettings ->
                    navController.navigate(ThundermailRoute.IncomingSettings)

                is ThundermailRoute.AccountSetup if route.accountId != null ->
                    messageListLauncher.launch(accountUuid = route.accountId)

                is ThundermailRoute.AccountSetup ->
                    navController.navigate(ThundermailRoute.AccountSetup())

                is ThundermailRoute.Permissions -> {
                    messageListLauncher.launch(route.accountId)
                    activity.finish()
                }

                is ThundermailRoute.AddAccount ->
                    navController.navigate(ThundermailRoute.AddAccount)

                is ThundermailRoute.SignInWithThundermail ->
                    navController.navigate(ThundermailRoute.SignInWithThundermail)

                is ThundermailRoute.ScanQrCode ->
                    navController.navigate(ThundermailRoute.ScanQrCode)

                else -> Unit
            }
        },
    )
}
