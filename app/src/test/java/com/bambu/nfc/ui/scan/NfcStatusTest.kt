package com.bambu.nfc.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcStatusTest {

    @Test
    fun `adapter presence and enabled flag map to a status`() {
        assertEquals(NfcStatus.Unsupported, NfcStatus.of(hasAdapter = false, isEnabled = false))
        assertEquals(NfcStatus.Unsupported, NfcStatus.of(hasAdapter = false, isEnabled = true))
        assertEquals(NfcStatus.Disabled, NfcStatus.of(hasAdapter = true, isEnabled = false))
        assertEquals(NfcStatus.Enabled, NfcStatus.of(hasAdapter = true, isEnabled = true))
    }

    @Test
    fun `no banner when NFC is on`() {
        assertNull(NfcStatus.Enabled.banner())
    }

    @Test
    fun `NFC off shows a banner with a settings button`() {
        val banner = NfcStatus.Disabled.banner()!!
        assertEquals("NFC is off", banner.title)
        assertTrue(banner.showSettingsButton)
    }

    @Test
    fun `no NFC hardware keeps its message and offers no settings button`() {
        val banner = NfcStatus.Unsupported.banner()!!
        assertEquals("This device does not support NFC", banner.title)
        assertFalse(banner.showSettingsButton)
    }
}
