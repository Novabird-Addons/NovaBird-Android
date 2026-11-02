package org.novabird.mail

import app.k9mail.feature.widget.shortcut.LauncherShortcutActivity
import com.fsck.k9.AppConfig
import com.fsck.k9.DefaultAppConfig
import com.fsck.k9.activity.MessageCompose
import net.thunderbird.app.common.appCommonModule
import net.thunderbird.core.common.oauth.OAuthConfigurationFactory
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.novabird.feature.ai.novaBirdAiModule
import org.novabird.feature.sync.novaBirdSyncModule
import org.novabird.mail.account.novaBirdAccountModule
import org.novabird.mail.ai.novaBirdAiAppModule
import org.novabird.mail.auth.NbOAuthConfigurationFactory
import org.novabird.mail.dev.developmentModuleAdditions
import org.novabird.mail.feature.featureModule
import org.novabird.mail.featureflag.novaBirdFeatureFlagModule
import org.novabird.mail.logo.novaBirdLogoModule
import org.novabird.mail.provider.providerModule
import org.novabird.mail.search.novaBirdSearchAppModule
import org.novabird.mail.widget.provider.MessageListWidgetProvider
import org.novabird.mail.widget.provider.UnreadWidgetProvider
import org.novabird.mail.widget.widgetModule

val appModule = module {
    includes(novaBirdFeatureFlagModule)
    includes(appCommonModule)

    includes(widgetModule)
    includes(featureModule)
    includes(providerModule)

    includes(novaBirdSyncModule)
    includes(novaBirdAccountModule)
    includes(novaBirdSearchAppModule)
    includes(novaBirdAiModule)
    includes(novaBirdAiAppModule)
    includes(novaBirdDrawerModule)
    includes(novaBirdLogoModule)

    single(named("ClientInfoAppName")) { BuildConfig.CLIENT_INFO_APP_NAME }
    single(named("ClientInfoAppVersion")) { BuildConfig.VERSION_NAME }
    single<AppConfig> { appConfig }
    single<OAuthConfigurationFactory> { NbOAuthConfigurationFactory() }

    developmentModuleAdditions()
}

val appConfig = DefaultAppConfig(
    componentsToDisable = listOf(
        MessageCompose::class.java,
        LauncherShortcutActivity::class.java,
        UnreadWidgetProvider::class.java,
        MessageListWidgetProvider::class.java,
    ),
)
