package org.novabird.feature.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.atom.icon.Icon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.novabird.feature.ai.R
import org.novabird.feature.vectors.HelperInfo

private const val STATUS_LABEL_WIDTH = 96

@Composable
internal fun HelperStatusView(helper: HelperStatus) {
    when (helper) {
        is HelperStatus.Connected -> HelperConnected(helper.info)
        is HelperStatus.Unreachable -> TextBodyMedium(text = errorText(helper.reason))
        HelperStatus.Checking -> TextBodyMedium(text = stringResource(R.string.novabird_ai_helper_checking))
        HelperStatus.NotPaired -> Unit
    }
}

/** One spaced line each, not a running text (user decision): connected with a green icon, device, group. */
@Composable
private fun HelperConnected(info: HelperInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.oneHalf)) {
        StatusLine(stringResource(R.string.novabird_ai_helper_connected)) {
            Icon(imageVector = Icons.Filled.CheckCircle, tint = BoltTheme.colors.success)
        }
        if (info.deviceName.isNotBlank()) {
            StatusLine(stringResource(R.string.novabird_ai_helper_device)) { TextBodyMedium(text = info.deviceName) }
        }
        info.group?.takeIf { it.isNotBlank() }?.let { group ->
            StatusLine(stringResource(R.string.novabird_ai_helper_group)) { TextBodyMedium(text = group) }
        }
        if (!info.separatesPhoneVectors) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Filled.Warning, tint = BoltTheme.colors.warning)
                TextBodyMedium(
                    text = stringResource(R.string.novabird_ai_helper_old),
                    modifier = Modifier.padding(start = BoltTheme.spacings.default),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextBodyMedium(
            text = label,
            color = BoltTheme.colors.onSurfaceVariant,
            modifier = Modifier.width(STATUS_LABEL_WIDTH.dp),
        )
        value()
    }
}
