package com.bambu.nfc.data.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpoolUriTest {

    private fun spool(id: Long) = SpoolmanCode.Spool(id)
    private fun filament(id: Long) = SpoolmanCode.Filament(id)

    @Test
    fun `new labels and stickers carry Spoolman's http url form`() {
        assertEquals("https://spoolman.lab.apj.dev/spool/show/123", SpoolUri.forSpool("https://spoolman.lab.apj.dev", 123))
        assertEquals("https://spoolman.lab.apj.dev/spool/show/123", SpoolUri.forSpool("https://spoolman.lab.apj.dev/", 123))
        assertEquals("https://spoolman.lab.apj.dev/spool/show/123", SpoolUri.forSpool(" https://spoolman.lab.apj.dev// ", 123))
        assertEquals("http://nas:7912/spoolman/spool/show/5", SpoolUri.forSpool("http://nas:7912/spoolman/", 5))
    }

    @Test
    fun `legacy scheme form matches Spoolman 0_26 labels`() {
        assertEquals("WEB+SPOOLMAN:S-123", SpoolUri.legacyForSpool(123))
    }

    @Test
    fun `parses spool and filament scheme codes in any case`() {
        assertEquals(spool(123), SpoolUri.parseCode("WEB+SPOOLMAN:S-123"))
        assertEquals(spool(123), SpoolUri.parseCode("web+spoolman:s-123"))
        assertEquals(spool(7), SpoolUri.parseCode("  Web+Spoolman:S-7\n"))
        assertEquals(filament(12), SpoolUri.parseCode("WEB+SPOOLMAN:F-12"))
        assertEquals(filament(12), SpoolUri.parseCode("web+SpoolMan:f-12"))
    }

    @Test
    fun `parses https and http urls on both hosts`() {
        assertEquals(spool(45), SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/45"))
        assertEquals(spool(45), SpoolUri.parseCode("https://spoolman.apj.dev/spool/show/45"))
        assertEquals(spool(45), SpoolUri.parseCode("http://spoolman.lab.apj.dev/spool/show/45"))
        assertEquals(spool(45), SpoolUri.parseCode("HTTPS://Spoolman.Lab.Apj.Dev/Spool/Show/45"))
        assertEquals(filament(3), SpoolUri.parseCode("https://spoolman.lab.apj.dev/filament/show/3"))
    }

    @Test
    fun `tolerates a trailing slash, query string, fragment, port and base path`() {
        assertEquals(spool(45), SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/45/"))
        assertEquals(spool(45), SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/45?ref=label"))
        assertEquals(spool(45), SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/45#top"))
        assertEquals(spool(45), SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/45/?a=1#b"))
        assertEquals(spool(45), SpoolUri.parseCode("http://192.168.1.5:7912/spoolman/spool/show/45"))
    }

    @Test
    fun `parse returns spool ids only`() {
        assertEquals(45L, SpoolUri.parse("https://spoolman.lab.apj.dev/spool/show/45"))
        assertEquals(9L, SpoolUri.parse("WEB+SPOOLMAN:S-9"))
        assertNull(SpoolUri.parse("WEB+SPOOLMAN:F-9"))
    }

    @Test
    fun `rejects anything that isn't a Spoolman code`() {
        assertNull(SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/abc"))
        assertNull(SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/"))
        assertNull(SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/show/12/edit"))
        assertNull(SpoolUri.parseCode("https://spoolman.lab.apj.dev/spool/12"))
        assertNull(SpoolUri.parseCode("https://example.com/"))
        assertNull(SpoolUri.parseCode("ftp://spoolman.lab.apj.dev/spool/show/12"))
        assertNull(SpoolUri.parseCode("WEB+SPOOLMAN:X-12"))
        assertNull(SpoolUri.parseCode("WEB+SPOOLMAN:S-"))
        assertNull(SpoolUri.parseCode("WEB+SPOOLMAN:S-12x"))
        assertNull(SpoolUri.parseCode("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(SpoolUri.parseCode(""))
    }
}
