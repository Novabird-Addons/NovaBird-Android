package org.novabird.feature.branding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.components.ui.bolt.theme.ThemeColorSchemeVariants
import net.thunderbird.components.ui.bolt.theme.ThemeConfig
import net.thunderbird.components.ui.bolt.theme.ThemeImageVariants
import net.thunderbird.components.ui.bolt.theme.ThemeImages
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import org.novabird.feature.branding.resources.Res
import org.novabird.feature.branding.resources.novabird_logo

/**
 * Bolt theme of NovaBird Mail: the colors, shapes and typography of the Thunderbird theme with the NovaBird logo.
 *
 * The color schemes of the Thunderbird theme aren't public, so they are taken from the enclosing
 * [ThunderbirdBoltTheme].
 */
@Composable
fun NovaBirdBoltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    ThunderbirdBoltTheme(darkTheme = darkTheme) {
        val colors = BoltTheme.colors
        val images = ThemeImages(logo = Res.drawable.novabird_logo)

        BoltTheme(
            themeConfig = ThemeConfig(
                colors = ThemeColorSchemeVariants(dark = colors, light = colors),
                elevations = BoltTheme.elevations,
                images = ThemeImageVariants(dark = images, light = images),
                shapes = BoltTheme.shapes,
                sizes = BoltTheme.sizes,
                spacings = BoltTheme.spacings,
                typography = BoltTheme.typography,
            ),
            darkTheme = darkTheme,
            content = content,
        )
    }
}
