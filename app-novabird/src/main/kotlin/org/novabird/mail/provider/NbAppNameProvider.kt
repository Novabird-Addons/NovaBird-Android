package org.novabird.mail.provider

import android.content.Context
import com.fsck.k9.preferences.FilePrefixProvider
import net.thunderbird.core.common.provider.AppNameProvider
import net.thunderbird.core.common.provider.BrandNameProvider
import org.novabird.mail.R

internal class NbAppNameProvider(
    context: Context,
) : AppNameProvider, BrandNameProvider, FilePrefixProvider {
    override val appName: String by lazy {
        context.getString(R.string.app_name)
    }

    override val brandName: String by lazy {
        context.getString(R.string.brand_name)
    }

    override val filePrefix: String = "novabird"
}
