package org.novabird.mail.provider

import net.thunderbird.feature.navigation.changelog.api.ChangelogConfigProvider
import org.novabird.mail.R

class NbChangelogConfigProvider : ChangelogConfigProvider {
    override val changelogIndexResId: Int = R.raw.changelog_index
}
