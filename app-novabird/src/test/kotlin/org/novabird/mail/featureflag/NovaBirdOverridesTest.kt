package org.novabird.mail.featureflag

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class NovaBirdOverridesTest {

    @Test
    fun `should turn off Thundermail onboarding in every build variant`() {
        val catalogOverrides = mapOf(
            "debug" to mapOf("display_in_app_notifications" to true),
            "release" to emptyMap(),
        )

        val testSubject = NovaBirdOverrides(catalogOverrides)

        assertThat(testSubject.debug).isEqualTo(
            mapOf(
                "display_in_app_notifications" to true,
                "thundermail_onboarding_enabled" to false,
            ),
        )
        assertThat(testSubject.release).isEqualTo(mapOf("thundermail_onboarding_enabled" to false))
    }

    @Test
    fun `should override a catalog value that enables Thundermail onboarding`() {
        val catalogOverrides = mapOf(
            "debug" to mapOf("thundermail_onboarding_enabled" to true),
            "release" to mapOf("thundermail_onboarding_enabled" to true),
        )

        val testSubject = NovaBirdOverrides(catalogOverrides)

        assertThat(testSubject.debug["thundermail_onboarding_enabled"]).isEqualTo(false)
        assertThat(testSubject.release["thundermail_onboarding_enabled"]).isEqualTo(false)
    }
}
