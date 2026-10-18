package com.bambu.nfc.domain

import com.bambu.nfc.data.labelito.SpoolLabel
import com.bambu.nfc.data.settings.SpoolLinks
import com.bambu.nfc.data.spoolman.ExtraValue
import com.bambu.nfc.data.spoolman.Filament
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.Vendor
import com.bambu.nfc.domain.model.FilamentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewSpoolSpecTest {

    private val tag = FilamentData(
        tagUid = "75886B1D", trayUid = "ignored", materialId = "GFA01", filamentType = "PLA",
        detailedType = "PLA Matte", colorRgba = 0xFFF99963.toInt(), weightGrams = 1000, diameterMm = 1.75f,
        dryingTempC = 55, dryingTimeHours = 8, bedTempC = 60, maxHotendTempC = 230, minHotendTempC = 190,
        productionDate = "2024-03-15", lengthMeters = 330,
    )

    @Test
    fun `builds a Spoolman spec from a Bambu tag`() {
        val spec = NewSpoolSpec.fromBambuTag(tag, "FE0F8D897AA94CB1B1E3DDC6862CFFE0")
        assertEquals("Bambu Lab", spec.vendorName)
        assertEquals("PLA Matte", spec.filamentName)
        assertEquals("PLA", spec.material)
        assertEquals("F99963", spec.colorHex)
        assertEquals("Mandarin Orange", spec.colorName)
        assertEquals(1.75, spec.diameterMm, 1e-9)
        assertEquals(1.24, spec.densityGcm3, 1e-9)
        assertEquals(1000.0, spec.netWeightG, 1e-9)
        assertEquals(210, spec.extruderTempC)
        assertEquals(60, spec.bedTempC)
        assertEquals("GFA01", spec.bambuMaterialId)
        assertEquals("FE0F8D897AA94CB1B1E3DDC6862CFFE0", spec.bambuTrayUuid)
        assertEquals("75886B1D", spec.nfcUid)
    }

    @Test
    fun `nozzle midpoint rounds to 5 degrees`() {
        assertEquals(210, NewSpoolSpec.midpoint(190, 230))
        assertEquals(255, NewSpoolSpec.midpoint(240, 270))
        assertEquals(220, NewSpoolSpec.midpoint(0, 220))
        assertNull(NewSpoolSpec.midpoint(0, 0))
    }

    @Test
    fun `matte black is Charcoal, basic black is Black`() {
        assertEquals("Charcoal", BambuColors.nameFor("000000", "PLA Matte"))
        assertEquals("Black", BambuColors.nameFor("#000000", "PLA Basic"))
        assertEquals("Bambu Green", BambuColors.nameFor("00AE43")) // nearest match
        assertNull(BambuColors.nameFor("123"))
    }

    private fun spool(extra: Map<String, String> = emptyMap(), name: String? = "PLA Matte", hex: String? = "F99963") = Spool(
        id = 123,
        filament = Filament(id = 1, name = name, vendor = Vendor(1, "Bambu Lab"), material = "PLA", colorHex = hex),
        extra = extra,
    )

    @Test
    fun `label fields for the spool-label template`() {
        assertEquals(
            mapOf(
                "material" to "PLA Matte",
                "color" to "Mandarin Orange",
                "vendor" to "Bambu Lab",
                "spool_id" to "#123",
                "qr" to "https://spoolman.lab.apj.dev/spool/show/123",
            ),
            SpoolLabel.fields(spool(), SpoolLinks { "https://spoolman.lab.apj.dev/" }.forSpool(123))
        )
    }

    @Test
    fun `a filament code prefills the form from the Spoolman filament`() {
        val f = Filament(
            id = 3, name = "PLA Matte", vendor = Vendor(1, "Bambu Lab"), material = "PLA", colorHex = "f99963",
            density = 1.31, diameter = 1.75, weight = 1000.0, spoolWeight = 250.0, settingsExtruderTemp = 220,
            settingsBedTemp = 60, extra = mapOf("bambu_material_id" to ExtraValue.encode("GFA01")),
        )
        val spec = NewSpoolSpec.fromFilament(f)
        assertEquals(3L, spec.filamentId)
        assertEquals("Bambu Lab", spec.vendorName)
        assertEquals("PLA Matte", spec.filamentName)
        assertEquals("F99963", spec.colorHex)
        assertEquals("Mandarin Orange", spec.colorName)
        assertEquals(1.31, spec.densityGcm3, 1e-9)
        assertEquals(250.0, spec.spoolWeightG!!, 1e-9)
        assertEquals(220, spec.extruderTempC)
        assertEquals("GFA01", spec.bambuMaterialId)
        assertNull(spec.nfcUid)
    }

    @Test
    fun `label colour prefers Bambuddy's colour name, then the table, then the hex`() {
        assertEquals("Glow Green", SpoolLabel.colorLine(spool(mapOf("bambu_color_name" to ExtraValue.encode("Glow Green")))))
        assertEquals("Dark Blue", SpoolLabel.colorLine(spool(hex = "123456"))) // nearest Bambu colour
        assertEquals("#7F00FF", SpoolLabel.colorLine(spool(hex = "7f00ff"))) // nothing close
        assertEquals("", SpoolLabel.colorLine(spool(hex = null)))
    }

    @Test
    fun `label material line`() {
        assertEquals("PLA Matte", SpoolLabel.materialLine("PLA", "PLA Matte"))
        assertEquals("PETG HF Black", SpoolLabel.materialLine("PETG", "HF Black"))
        assertEquals("PLA", SpoolLabel.materialLine("PLA", null))
        assertEquals("Mystery", SpoolLabel.materialLine(null, "Mystery"))
    }

    @Test
    fun `extra values are JSON-encoded strings`() {
        assertEquals("\"abc\"", ExtraValue.encode("abc"))
        assertEquals("abc", ExtraValue.decode("\"abc\""))
        assertEquals("a\"b", ExtraValue.decode(ExtraValue.encode("a\"b")))
        assertEquals("raw", ExtraValue.decode("raw")) // tolerate a non-JSON value
        assertNull(ExtraValue.decode(null))
    }
}
