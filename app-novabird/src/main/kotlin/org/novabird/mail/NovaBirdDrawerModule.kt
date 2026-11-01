package org.novabird.mail

import android.content.Intent
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.feature.navigation.drawer.api.DrawerExtraAction
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.novabird.feature.ai.ui.AiNewsActivity
import org.novabird.feature.ai.ui.AiSettingsActivity
import org.novabird.feature.sync.ui.SyncLimitSettingsActivity
import org.novabird.feature.ai.R as AiR

/** NovaBird entries in the drawer's settings list (above "Settings"). */
val novaBirdDrawerModule = module {
    single(named("novabird_news")) {
        DrawerExtraAction(AiR.string.novabird_ai_menu_news, Icons.Outlined.AllInbox) { activity ->
            activity.startActivity(Intent(activity, AiNewsActivity::class.java))
        }
    }
    single(named("novabird_ai")) {
        DrawerExtraAction(R.string.novabird_shortcut_ai_short, Icons.Outlined.Rocket) { activity ->
            activity.startActivity(Intent(activity, AiSettingsActivity::class.java))
        }
    }
    single(named("novabird_sync")) {
        DrawerExtraAction(R.string.novabird_shortcut_sync_limits_short, Icons.Outlined.Sync) { activity ->
            activity.startActivity(Intent(activity, SyncLimitSettingsActivity::class.java))
        }
    }
}
