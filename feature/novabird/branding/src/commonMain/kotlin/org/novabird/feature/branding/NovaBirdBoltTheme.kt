package org.novabird.feature.branding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.components.ui.bolt.theme.ThemeColorSchemeVariants
import net.thunderbird.components.ui.bolt.theme.ThemeConfig
import net.thunderbird.components.ui.bolt.theme.ThemeImageVariants
import net.thunderbird.components.ui.bolt.theme.ThemeImages
import net.thunderbird.components.ui.bolt.theme.ThemeShapes
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import org.novabird.feature.branding.resources.Res
import org.novabird.feature.branding.resources.novabird_logo

/** Rounder corners than Material's defaults: cards and sheets 20 dp, like the NovaBird design. */
private val novaBirdShapes = ThemeShapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Bolt theme of NovaBird Mail: NovaBird colors and shapes, the sizes, spacings and typography of the Thunderbird
 * theme and the NovaBird logo.
 *
 * Sizes, spacings and typography of the Thunderbird theme aren't public, so they are taken from the enclosing
 * [ThunderbirdBoltTheme].
 */
@Composable
fun NovaBirdBoltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    ThunderbirdBoltTheme(darkTheme = darkTheme) {
        val images = ThemeImages(logo = Res.drawable.novabird_logo)

        BoltTheme(
            themeConfig = ThemeConfig(
                colors = ThemeColorSchemeVariants(dark = novaBirdDarkColors, light = novaBirdLightColors),
                elevations = BoltTheme.elevations,
                images = ThemeImageVariants(dark = images, light = images),
                shapes = novaBirdShapes,
                sizes = BoltTheme.sizes,
                spacings = BoltTheme.spacings,
                typography = BoltTheme.typography,
            ),
            darkTheme = darkTheme,
            content = content,
        )
    }
}
