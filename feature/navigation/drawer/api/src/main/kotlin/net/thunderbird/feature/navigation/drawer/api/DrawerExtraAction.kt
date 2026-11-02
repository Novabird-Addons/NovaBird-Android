package net.thunderbird.feature.navigation.drawer.api

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Extra entry in the settings list at the bottom of the drawer, contributed by an app (NovaBird) through Koin
 * (all bound instances are shown, in order, above "Settings"). Without any binding the drawer is unchanged.
 */
class DrawerExtraAction(
    @StringRes val label: Int,
    val icon: ImageVector,
    val onClick: (Activity) -> Unit,
)
