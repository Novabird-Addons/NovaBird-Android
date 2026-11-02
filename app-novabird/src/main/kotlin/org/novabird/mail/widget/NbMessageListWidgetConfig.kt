package org.novabird.mail.widget

import app.k9mail.feature.widget.message.list.MessageListWidgetConfig
import org.novabird.mail.widget.provider.MessageListWidgetProvider

class NbMessageListWidgetConfig : MessageListWidgetConfig {
    override val providerClass = MessageListWidgetProvider::class.java
}
