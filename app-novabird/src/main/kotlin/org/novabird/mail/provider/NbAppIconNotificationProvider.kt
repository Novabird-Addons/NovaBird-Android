package org.novabird.mail.provider

import app.k9mail.core.android.common.provider.NotificationIconResourceProvider
import org.novabird.mail.R

class NbAppIconNotificationProvider : NotificationIconResourceProvider {
    override val pushNotificationIcon: Int
        get() = R.drawable.ic_novabird_notification
}
