package com.bambu.nfc.domain

import com.bambu.nfc.data.spoolman.ExtraValue
import com.bambu.nfc.data.spoolman.Filament
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpoolMatcherTest {

    private val bambu = Vendor(1, "Bambu Lab")
    private val other = Vendor(2, "Overture")

    private fun filament(id: Long, name: String?, hex: String, vendor: Vendor? = bambu, materialId: String? = null, diameter: Double = 1.75) =
        Filament(
            id = id, name = name, vendor = vendor, material = "PLA", colorHex = hex, diameter = diameter,
            extra = materialId?.let { mapOf("bambu_material_id" to ExtraValue.encode(it)) } ?: emptyMap()
        )

    private fun spool(id: Long, archived: Boolean = false, vararg extra: Pair<String, String>) =
        Spool(id = id, filament = filament(1, "PLA Matte", "000000"), archived = archived,
            extra = extra.associate { (k, v) -> k to ExtraValue.encode(v) })

    private val tray = "FE0F8D897AA94CB1B1E3DDC6862CFFE0"

    @Test
    fun `matches Bambu spool by our tray uuid field, case-insensitively`() {
        val spools = listOf(spool(1), spool(2, false, "bambu_tray_uuid" to tray.lowercase()))
        assertEquals(2L, SpoolMatcher.findBambuSpool(spools, tray, "11223344")?.id)
    }

    @Test
    fun `matches spools Bambuddy created via its tag field`() {
        // Exactly what Spoolman returns for a Bambuddy-created spool: a JSON-encoded string.
        val bambuddy = Spool(id = 5, filament = filament(2, "PLA Matte", "000000"),
            extra = mapOf("tag" to "\"$tray\"", "bambu_color_name" to "\"Charcoal\""))
        assertEquals(5L, SpoolMatcher.findBambuSpool(listOf(spool(1), bambuddy), tray, "11223344")?.id)
    }

    @Test
    fun `both tags on one spool resolve to the same spool`() {
        val spools = listOf(spool(3, false, "bambu_tray_uuid" to tray, "nfc_uid" to "AAAA0001"))
        assertEquals(3L, SpoolMatcher.findBambuSpool(spools, tray, "AAAA0001")?.id)
        assertEquals(3L, SpoolMatcher.findBambuSpool(spools, tray, "BBBB0002")?.id)
    }

    @Test
    fun `falls back to a learned uid when the tag couldn't be decoded`() {
        val spools = listOf(spool(4, false, "nfc_uid" to "AAAA0001,BBBB0002"))
        assertEquals(4L, SpoolMatcher.findBambuSpool(spools, null, "bbbb0002")?.id)
        assertEquals(4L, SpoolMatcher.findByNfcUid(spools, "AAAA0001")?.id)
        assertNull(SpoolMatcher.findByNfcUid(spools, "CCCC0003"))
    }

    @Test
    fun `prefers an active spool over an archived one, then the newest`() {
        val spools = listOf(
            spool(10, true, "bambu_tray_uuid" to tray),
            spool(7, false, "bambu_tray_uuid" to tray),
            spool(8, false, "bambu_tray_uuid" to tray),
        )
        assertEquals(8L, SpoolMatcher.findBambuSpool(spools, tray, "X")?.id)
    }

    @Test
    fun `no match returns null`() {
        assertNull(SpoolMatcher.findBambuSpool(listOf(spool(1)), tray, "X"))
    }

    @Test
    fun `nfc uid list appends once`() {
        val s = spool(1, false, "nfc_uid" to "AAAA0001")
        assertEquals("AAAA0001,BBBB0002", SpoolMatcher.nfcUidsWith(s, "bbbb0002"))
        assertNull(SpoolMatcher.nfcUidsWith(s, "aaaa0001"))
        assertEquals("CCCC0003", SpoolMatcher.nfcUidsWith(spool(2), "CCCC0003"))
    }

    @Test
    fun `vendor match ignores case and whitespace`() {
        assertEquals(1L, SpoolMatcher.matchVendor(listOf(other, bambu), " bambu lab ")?.id)
        assertNull(SpoolMatcher.matchVendor(listOf(other), "Bambu Lab"))
    }

    private fun spec(name: String, hex: String = "000000", materialId: String? = null, diameter: Double = 1.75) =
        NewSpoolSpec(vendorName = "Bambu Lab", filamentName = name, material = "PLA", colorHex = hex,
            bambuMaterialId = materialId, diameterMm = diameter)

    @Test
    fun `same material and colour but different product is not the same filament`() {
        val filaments = listOf(filament(1, "PLA Matte", "000000"), filament(2, "PLA Basic", "000000"))
        assertEquals(2L, SpoolMatcher.matchFilament(filaments, spec("PLA Basic"), 1)?.id)
        assertEquals(1L, SpoolMatcher.matchFilament(filaments, spec("pla matte"), 1)?.id)
        assertNull(SpoolMatcher.matchFilament(filaments, spec("PLA Silk"), 1))
    }

    @Test
    fun `bambu material id wins over name`() {
        val filaments = listOf(filament(1, "PLA Matte", "000000"), filament(2, "Matte black", "000000", materialId = "GFA01"))
        assertEquals(2L, SpoolMatcher.matchFilament(filaments, spec("PLA Matte", materialId = "GFA01"), 1)?.id)
    }

    @Test
    fun `colour, diameter and vendor must all match`() {
        val filaments = listOf(
            filament(1, "PLA Matte", "000000", vendor = other),
            filament(2, "PLA Matte", "000000", diameter = 2.85),
            filament(3, "PLA Matte", "FFFFFF"),
        )
        assertNull(SpoolMatcher.matchFilament(filaments, spec("PLA Matte"), 1))
        assertNull(SpoolMatcher.matchFilament(filaments, spec("PLA Matte"), null))
    }

    @Test
    fun `colour hex compares without hash and case`() {
        val filaments = listOf(filament(1, "PLA Matte", "f99963"))
        assertEquals(1L, SpoolMatcher.matchFilament(filaments, spec("PLA Matte", hex = "#F99963"), 1)?.id)
    }

    @Test
    fun `an unnamed filament with matching attributes is reused`() {
        val filaments = listOf(filament(9, null, "000000"))
        assertEquals(9L, SpoolMatcher.matchFilament(filaments, spec("PLA Matte"), 1)?.id)
    }
}
