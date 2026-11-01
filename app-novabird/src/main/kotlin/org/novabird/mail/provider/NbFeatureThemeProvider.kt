package org.novabird.mail.provider

import androidx.compose.runtime.Composable
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.novabird.feature.branding.NovaBirdBoltTheme

internal class NbFeatureThemeProvider : FeatureThemeProvider {
    @Composable
    override fun WithTheme(content: @Composable () -> Unit) {
        NovaBirdBoltTheme {
            content()
        }
    }

    @Composable
    override fun WithTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
        NovaBirdBoltTheme(darkTheme = darkTheme) {
            content()
        }
    }
}
