package org.novabird.mail.account

import app.k9mail.feature.account.setup.AccountSetupExternalContract.MessageDisplayCountProvider
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import assertk.assertThat
import assertk.assertions.isSameInstanceAs
import org.junit.Test
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication

class NovaBirdAccountModuleTest {

    @Test
    fun `setup wizard should preselect all messages`() {
        val koin = koinApplication { modules(novaBirdAccountModule) }.koin
        val testSubject = koin.get<MessageDisplayCountProvider>(named("app"))

        val result = testSubject.getDefaultMessageDisplayCount()

        assertThat(result).isSameInstanceAs(EmailDisplayCount.ALL)
    }
}
