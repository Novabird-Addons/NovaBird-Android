package org.novabird.mail.widget

import app.k9mail.feature.widget.unread.UnreadWidgetConfig
import org.novabird.mail.widget.provider.UnreadWidgetProvider

class NbUnreadWidgetConfig : UnreadWidgetConfig {
    override val providerClass = UnreadWidgetProvider::class.java
}
