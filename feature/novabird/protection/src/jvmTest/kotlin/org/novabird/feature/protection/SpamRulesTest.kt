package org.novabird.feature.protection

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject

class SpamRulesTest {
    private val on = SpamSettings(enabled = true)

    private fun verdict(vararg signals: Pair<String, List<String>>, level: ProtectionLevel = ProtectionLevel.NORMAL) =
        Verdict(0, level, signals.map { (id, params) -> Signal(id, 0.0, params) }, JsonObject(emptyMap()))

    @Suppress("LongParameterList")
    private fun decide(
        verdict: Verdict = verdict(),
        settings: SpamSettings = on,
        sender: String = "x@fremd.example",
        headers: Map<String, List<String>> = emptyMap(),
        known: Boolean = false,
        trusted: Boolean = false,
    ) = SpamRules.decide(sender, headers, verdict, known, trusted, settings)

    @Test
    fun `nothing is spam while the detection is off`() {
        assertThat(decide(settings = SpamSettings(blocked = listOf("fremd.example")))).isNull()
    }

    @Test
    fun `blocked addresses and domains with subdomains are spam, allowed and known senders never`() {
        val settings = on.copy(blocked = listOf("fremd.example", "a@b.example"))

        assertThat(decide(settings = settings)?.reason).isEqualTo(SpamReason.BLOCKED)
        assertThat(decide(settings = settings, sender = "y@mail.fremd.example")?.detail).isEqualTo("fremd.example")
        assertThat(decide(settings = settings, sender = "a@b.example")?.detail).isEqualTo("a@b.example")
        assertThat(decide(settings = settings, sender = "c@b.example")).isNull()
        assertThat(decide(settings = settings, sender = "y@nichtfremd.example")).isNull()
        assertThat(decide(settings = settings.copy(allowed = listOf("x@fremd.example")))).isNull()
        assertThat(decide(settings = settings, known = true)).isNull()
        assertThat(decide(settings = settings, trusted = true)).isNull()
    }

    @Test
    fun `the server's spam mark counts when switched on`() {
        val flag = mapOf("x-spam-flag" to listOf(" YES "))
        val status = mapOf("x-spam-status" to listOf("Yes, score=9.1 required=5.0"))

        assertThat(decide(headers = flag)?.reason).isEqualTo(SpamReason.SERVER)
        assertThat(decide(headers = status)?.reason).isEqualTo(SpamReason.SERVER)
        assertThat(decide(headers = mapOf("x-spam-status" to listOf("No, score=0.1")))).isNull()
        assertThat(decide(headers = flag, settings = on.copy(serverFlag = false))).isNull()
    }

    @Test
    fun `a failed DMARC or hard SPF check of an unknown sender is spam, a soft fail or DKIM alone is not`() {
        assertThat(decide(verdict("dmarcFail" to emptyList()))?.detail).isEqualTo("dmarc")
        assertThat(decide(verdict("spfFail" to listOf("fail")))?.detail).isEqualTo("spf")
        assertThat(decide(verdict("spfFail" to listOf("softfail")))).isNull()
        assertThat(decide(verdict("dkimFail" to emptyList()))).isNull()
        assertThat(decide(verdict("dmarcFail" to emptyList()), known = true)).isNull()
        assertThat(decide(verdict("dmarcFail" to emptyList()), settings = on.copy(authFail = false))).isNull()
    }

    @Test
    fun `dangerous phishing is spam only when chosen`() {
        val dangerous = verdict("lookalikeDomain" to emptyList(), level = ProtectionLevel.DANGEROUS)

        assertThat(decide(dangerous)).isNull()
        assertThat(decide(dangerous, settings = on.copy(phishing = true))?.reason).isEqualTo(SpamReason.PHISHING)
    }

    @Test
    fun `money spam needs two signals and the threshold, weak signals never fire alone`() {
        assertThat(decide(verdict("randomDomain" to emptyList(), "fillerText" to emptyList()))?.reason)
            .isEqualTo(SpamReason.MONEY)
        assertThat(decide(verdict("investmentPromo" to emptyList(), "badTld" to emptyList()))).isNull()
        assertThat(decide(verdict("investmentPromo" to emptyList()))).isNull()
        val weak = verdict("imageOnly" to emptyList(), "badTld" to emptyList(), "invisiblePadding" to emptyList())
        assertThat(decide(weak)).isNull()
    }

    @Test
    fun `entries are normalized and settings survive JSON`() {
        assertThat(SpamRules.normalizeEntry(" @Fremd.Example ")).isEqualTo("fremd.example")
        assertThat(SpamRules.normalizeEntry("A@B.example")).isEqualTo("a@b.example")
        assertThat(SpamRules.normalizeEntry("kein eintrag")).isNull()
        assertThat(SpamRules.normalizeEntry("localhost")).isNull()

        val settings = SpamSettings(true, false, true, true, listOf("a.example"), listOf("b@c.example"))
        assertThat(SpamSettings.fromJson(settings.toJson())).isEqualTo(settings)
        assertThat(SpamSettings.fromJson("kaputt")).isEqualTo(SpamSettings())
    }
}
