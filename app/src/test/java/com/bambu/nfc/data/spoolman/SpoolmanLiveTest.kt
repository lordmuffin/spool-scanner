package com.bambu.nfc.data.spoolman

import com.bambu.nfc.domain.NewSpoolSpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end against a real Spoolman, skipped unless SPOOLMAN_LIVE_URL is set.
 * Point it at a THROWAWAY container (see README), never the homelab instance: it creates
 * extra fields, a vendor, filaments and spools and leaves them there.
 */
class SpoolmanLiveTest {

    private val url: String? = System.getenv("SPOOLMAN_LIVE_URL")

    @Test
    fun `full flow against a throwaway Spoolman`() = runBlocking {
        assumeTrue("SPOOLMAN_LIVE_URL not set", !url.isNullOrBlank())
        val repo = SpoolmanRepository(SpoolmanApiProvider(OkHttpClient()) { url!! })

        repo.ensureExtraFields()
        assertEquals(emptyList<String>(), repo.ensureExtraFields()) // idempotent

        val tray = "AD91AD0D779F4DEDB5C3E7CF0AC625E2"
        val spec = NewSpoolSpec(
            vendorName = "Bambu Lab", filamentName = "PLA Matte", material = "PLA", colorHex = "000000",
            colorName = "Charcoal", netWeightG = 1000.0, spoolWeightG = 250.0, extruderTempC = 220, bedTempC = 60,
            bambuMaterialId = "GFA01", bambuTrayUuid = tray, nfcUid = "AAAA0001",
        )
        val created = repo.createSpool(spec)
        assertEquals(tray, created.extraValue(ExtraKeys.SPOOL_TRAY_UUID))
        assertEquals("GFA01", created.filament.extraValue(ExtraKeys.FILAMENT_MATERIAL_ID))

        // The second tag on the same spool: same tray UUID, different UID → same spool, UID learned.
        val found = repo.findBambuSpool(tray, "BBBB0002")
        assertEquals(created.id, found?.id)
        assertEquals("AAAA0001,BBBB0002", found!!.extraValue(ExtraKeys.SPOOL_NFC_UID))
        assertEquals(created.id, repo.findByNfcUid("bbbb0002")?.id)

        // A second spool of the same product reuses the filament.
        val second = repo.createSpool(spec.copy(bambuTrayUuid = "00112233445566778899AABBCCDDEEFF", nfcUid = null))
        assertEquals(created.filament.id, second.filament.id)

        val measured = repo.measure(created.id, 1150.0) // 1150 gross - 250 spool = 900 net
        assertEquals(900.0, measured.remainingWeight!!, 0.5)
        val used = repo.use(created.id, 100.0)
        assertEquals(800.0, used.remainingWeight!!, 0.5)
        assertEquals("Shelf A", repo.setLocation(created.id, "Shelf A").location)
        assertTrue(repo.setArchived(created.id, true).archived)
        // Location patch must not have wiped the extra fields (Spoolman merges extra per key).
        assertNotNull(repo.getSpool(created.id)!!.extraValue(ExtraKeys.SPOOL_TRAY_UUID))
    }
}
