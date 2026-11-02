package org.novabird.mail.logo

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class BimiRecordsTest {

    @Test
    fun `should take the domain of an address in lower case`() {
        assertThat(BimiRecords.domainOf("Security@Mail.Instagram.com")).isEqualTo("mail.instagram.com")
    }

    @Test
    fun `should reject domains that aren't plain DNS names`() {
        assertThat(BimiRecords.domainOf("someone")).isNull()
        assertThat(BimiRecords.domainOf("a@localhost")).isNull()
        assertThat(BimiRecords.domainOf("a@exa mple.com")).isNull()
        assertThat(BimiRecords.domainOf("a@[127.0.0.1]")).isNull()
    }

    @Test
    fun `should find the organizational domain`() {
        assertThat(BimiRecords.organizationalDomain("mail.instagram.com")).isEqualTo("instagram.com")
        assertThat(BimiRecords.organizationalDomain("instagram.com")).isEqualTo("instagram.com")
        assertThat(BimiRecords.organizationalDomain("news.bbc.co.uk")).isEqualTo("bbc.co.uk")
    }

    @Test
    fun `should read the https logo of a BIMI record`() {
        val record = "v=BIMI1; l=https://example.com/logo.svg; a=https://example.com/vmc.pem"

        assertThat(BimiRecords.logoUrl(record)).isEqualTo("https://example.com/logo.svg")
    }

    @Test
    fun `should ignore records without BIMI1 or with plain http`() {
        assertThat(BimiRecords.logoUrl("v=spf1 include:example.com ~all")).isNull()
        assertThat(BimiRecords.logoUrl("v=BIMI1; l=http://example.com/logo.svg")).isNull()
        assertThat(BimiRecords.logoUrl("v=BIMI1; l=;")).isNull()
    }
}
