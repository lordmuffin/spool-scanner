package com.bambu.nfc.data.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpoolUriTest {

    @Test
    fun `formats like Spoolman's own QR labels`() {
        assertEquals("WEB+SPOOLMAN:S-123", SpoolUri.forSpool(123))
    }

    @Test
    fun `parses the scheme form in any case`() {
        assertEquals(123L, SpoolUri.parse("WEB+SPOOLMAN:S-123"))
        assertEquals(123L, SpoolUri.parse("web+spoolman:s-123"))
        assertEquals(7L, SpoolUri.parse("  Web+Spoolman:S-7\n"))
    }

    @Test
    fun `parses Spoolman's http url form`() {
        assertEquals(45L, SpoolUri.parse("https://spoolman.lab.apj.dev/spool/show/45"))
        assertEquals(45L, SpoolUri.parse("http://192.168.1.5:7912/spoolman/spool/show/45/"))
    }

    @Test
    fun `rejects other payloads`() {
        assertNull(SpoolUri.parse("web+spoolman:f-12")) // filament code, not a spool
        assertNull(SpoolUri.parse("WEB+SPOOLMAN:S-"))
        assertNull(SpoolUri.parse("WEB+SPOOLMAN:S-12x"))
        assertNull(SpoolUri.parse("https://example.com/spool/12"))
        assertNull(SpoolUri.parse(""))
    }
}
