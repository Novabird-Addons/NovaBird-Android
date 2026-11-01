package org.novabird.mail.auth

import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.core.common.oauth.OAuthConfigurationFactory

/**
 * NovaBird has no OAuth client IDs of its own yet. The client IDs of Thunderbird are registered for Thunderbird's
 * application ID and must not be reused. Until own IDs exist, accounts use password authentication only.
 */
class NbOAuthConfigurationFactory : OAuthConfigurationFactory {
    override fun createConfigurations(): Map<List<String>, OAuthConfiguration> = emptyMap()
}
