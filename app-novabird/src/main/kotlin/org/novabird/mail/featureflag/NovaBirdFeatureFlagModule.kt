package org.novabird.mail.featureflag

import net.thunderbird.core.featureflag.inject.featureFlagModule
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
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
 *
 * NovaBird uses the Thunderbird flags of the catalog and adds [NOVABIRD_OVERRIDES] to every build variant.
 */
class NovaBirdOverrides(wrapper: Map<String, FlagOverrides>) :
    BaseAppVariantOverrides(wrapper.mapValues { (_, overrides) -> overrides + NOVABIRD_OVERRIDES }) {
    companion object {
        val Factory = AppVariantOverrides.Factory { wrapper -> NovaBirdOverrides(wrapper) }

        internal val NOVABIRD_OVERRIDES: FlagOverrides = mapOf(
            // Thundermail is a Thunderbird service: hide its sign-in and QR code buttons in the account setup.
            GeneratedFeatureFlagKey.THUNDERMAIL_ONBOARDING_ENABLED.key to false,
        )
    }
}
