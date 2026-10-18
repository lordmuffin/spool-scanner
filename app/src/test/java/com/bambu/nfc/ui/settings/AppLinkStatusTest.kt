package com.bambu.nfc.ui.settings

import android.content.pm.verify.domain.DomainVerificationUserState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLinkStatusTest {

    @Test
    fun `domain states map to what Settings shows`() {
        assertEquals(LinkState.Verified, AppLinkStatus.stateOf(DomainVerificationUserState.DOMAIN_STATE_VERIFIED, true))
        assertEquals(LinkState.Selected, AppLinkStatus.stateOf(DomainVerificationUserState.DOMAIN_STATE_SELECTED, true))
        assertEquals(LinkState.NotYet, AppLinkStatus.stateOf(DomainVerificationUserState.DOMAIN_STATE_NONE, true))
        assertEquals(LinkState.NotYet, AppLinkStatus.stateOf(null, true))
    }

    @Test
    fun `link handling switched off wins over a verified domain`() {
        assertEquals(LinkState.Disabled, AppLinkStatus.stateOf(DomainVerificationUserState.DOMAIN_STATE_VERIFIED, false))
    }

    @Test
    fun `checks both the lab host and the promoted host`() {
        assertEquals(listOf("spoolman.lab.apj.dev", "spoolman.apj.dev"), AppLinkStatus.HOSTS)
    }
}
