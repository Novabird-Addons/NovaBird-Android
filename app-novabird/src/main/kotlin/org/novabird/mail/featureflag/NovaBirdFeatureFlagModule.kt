package org.novabird.mail.featureflag

import net.thunderbird.core.featureflag.inject.featureFlagModule
import net.thunderbird.core.featureflag.model.AppVariantOverrides
import net.thunderbird.core.featureflag.model.BaseAppVariantOverrides
import net.thunderbird.core.featureflag.model.EmptyAppVariantOverride
import net.thunderbird.core.featureflag.model.FlagOverrides
import net.thunderbird.core.featureflag.serialization.FlagRegistryOverrideSerializer
import org.koin.dsl.module

val novaBirdFeatureFlagModule = module {
    includes(featureFlagModule)
    factory<AppVariantOverrides.Factory> { NovaBirdOverrides.Factory }
    factory { FlagRegistryOverrideSerializer(k9Factory = EmptyAppVariantOverride, thunderbirdFactory = get()) }
}

/**
 * Feature flag overrides for the NovaBird build variants (debug, release).
 */
class NovaBirdOverrides(wrapper: Map<String, FlagOverrides>) : BaseAppVariantOverrides(wrapper) {
    companion object {
        val Factory = AppVariantOverrides.Factory { wrapper -> NovaBirdOverrides(wrapper) }
    }
}
