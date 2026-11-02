package net.thunderbird.feature.navigation.drawer.dropdown.ui.setting

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import net.thunderbird.feature.navigation.drawer.api.DrawerExtraAction

/** App entries for the settings list (see [DrawerExtraAction]); clicking one runs it and closes the drawer. */
internal class DrawerExtraActions(val actions: List<DrawerExtraAction>, val onClick: (DrawerExtraAction) -> Unit)

// NovaBird: a CompositionLocal keeps the upstream diff small (no changes to the drawer's contract and view model)
@Suppress("CompositionLocalAllowlist")
internal val LocalDrawerExtraActions = staticCompositionLocalOf { DrawerExtraActions(emptyList()) {} }

internal fun LazyGridScope.extraActionItems(extra: DrawerExtraActions, hideText: Boolean) {
    for (action in extra.actions) {
        item(span = { if (hideText) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
            SettingListItem(
                label = stringResource(id = action.label),
                onClick = { extra.onClick(action) },
                icon = action.icon,
            )
        }
    }
}
