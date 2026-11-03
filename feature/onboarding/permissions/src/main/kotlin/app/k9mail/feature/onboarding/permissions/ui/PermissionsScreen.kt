package app.k9mail.feature.onboarding.permissions.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.k9mail.feature.onboarding.permissions.ui.PermissionsContract.Effect
import app.k9mail.feature.onboarding.permissions.ui.PermissionsContract.Event
import net.thunderbird.core.common.provider.BrandNameProvider
import net.thunderbird.core.ui.contract.mvi.observe
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PermissionsScreen(
    viewModel: PermissionsContract.ViewModel = koinViewModel<PermissionsViewModel>(),
    brandNameProvider: BrandNameProvider = koinInject(),
    onNext: () -> Unit,
) {
    val contactsPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { success ->
        viewModel.event(Event.ContactsPermissionResult(success))
    }

    val notificationsPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { success ->
        viewModel.event(Event.NotificationsPermissionResult(success))
    }

    val backgroundActivityLauncher = rememberLauncherForActivityResult(StartActivityForResult()) {
        viewModel.event(Event.BackgroundActivityResult)
    }
    val packageName = LocalContext.current.packageName

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.RequestContactsPermission -> contactsPermissionLauncher.requestContactsPermission()
            Effect.RequestNotificationsPermission -> notificationsPermissionLauncher.requestNotificationsPermission()
            Effect.RequestBackgroundActivity -> backgroundActivityLauncher.requestBackgroundActivity(packageName)
            Effect.OpenAppSettings -> backgroundActivityLauncher.openAppSettings(packageName)
            Effect.NavigateNext -> onNext()
        }
    }

    BackHandler {
        dispatch(Event.NextClicked)
    }

    LaunchedEffect(key1 = Unit) {
        dispatch(Event.LoadPermissionState)
    }

    PermissionsContent(
        state = state.value,
        onEvent = dispatch,
        brandName = brandNameProvider.brandName,
    )
}

private fun ManagedActivityResultLauncher<String, Boolean>.requestContactsPermission() {
    launch(Manifest.permission.READ_CONTACTS)
}

// the system dialog "Stop optimizing battery usage?"; if a phone doesn't have it, the list of apps in the settings
@SuppressLint("BatteryLife")
private fun ManagedActivityResultLauncher<Intent, ActivityResult>.requestBackgroundActivity(packageName: String) {
    try {
        launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    } catch (_: ActivityNotFoundException) {
        launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

// some phones (e.g. OPPO, OnePlus, Xiaomi) have their own switch "Allow background activity" in the app info
private fun ManagedActivityResultLauncher<Intent, ActivityResult>.openAppSettings(packageName: String) {
    launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

private fun ManagedActivityResultLauncher<String, Boolean>.requestNotificationsPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
