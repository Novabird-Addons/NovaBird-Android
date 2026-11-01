package org.novabird.mail.provider

import net.thunderbird.core.ui.theme.api.ThemeProvider
import org.novabird.mail.R

internal class NbThemeProvider : ThemeProvider {
    override val appThemeResourceId = R.style.Theme_NovaBird_DayNight
    override val appLightThemeResourceId = R.style.Theme_NovaBird_Light
    override val appDarkThemeResourceId = R.style.Theme_NovaBird_Dark
    override val dialogThemeResourceId = R.style.Theme_NovaBird_DayNight_Dialog
    override val translucentDialogThemeResourceId = R.style.Theme_NovaBird_DayNight_Dialog_Translucent
}
