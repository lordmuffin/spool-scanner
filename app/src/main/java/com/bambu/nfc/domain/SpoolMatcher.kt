package com.bambu.nfc.domain

import com.bambu.nfc.data.spoolman.ExtraKeys
import com.bambu.nfc.data.spoolman.Filament
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.Vendor
import com.bambu.nfc.data.spoolman.extraValue
import kotlin.math.abs

/**
 * Pure matching rules between a scanned tag and Spoolman's lists. Spoolman's server-side
 * extra-field filters compare JSON-encoded values, so we list and filter client-side instead.
 */
object SpoolMatcher {

    /**
     * A Bambu spool has two tags with different UIDs but the same tray UUID. Matches, in order:
     * our `bambu_tray_uuid`, Bambuddy's `tag` (it stores the AMS tray_uuid there), then a UID we
     * learned earlier in `nfc_uid`. Unarchived spools win over archived ones, then the newest.
     */
    fun findBambuSpool(spools: List<Spool>, trayUuid: String?, tagUid: String?): Spool? {
        val uid = tagUid?.uppercase()
        val byTray = trayUuid?.let { tray ->
            spools.filter {
                it.extraValue(ExtraKeys.SPOOL_TRAY_UUID).equals(tray, ignoreCase = true) ||
                    it.extraValue(ExtraKeys.BAMBUDDY_TAG).equals(tray, ignoreCase = true)
            }
        }.orEmpty()
        val chosen = preferred(byTray)
        if (chosen != null) return chosen
        if (uid == null) return null
        return preferred(spools.filter { uid in nfcUids(it) || it.extraValue(ExtraKeys.BAMBUDDY_TAG).equals(uid, ignoreCase = true) })
    }

    fun findByNfcUid(spools: List<Spool>, tagUid: String): Spool? =
        preferred(spools.filter { tagUid.uppercase() in nfcUids(it) })

    /** UIDs recorded on a spool. `nfc_uid` holds a comma-separated list (a Bambu spool has two tags). */
    fun nfcUids(spool: Spool): Set<String> =
        spool.extraValue(ExtraKeys.SPOOL_NFC_UID)
            ?.split(',', ' ', ';')
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    /** The new `nfc_uid` value with [tagUid] appended, or null when it is already recorded. */
    fun nfcUidsWith(spool: Spool, tagUid: String): String? {
        val uid = tagUid.uppercase()
        val current = nfcUids(spool)
        if (uid in current) return null
        return (current.sorted() + uid).joinToString(",")
    }

    fun matchVendor(vendors: List<Vendor>, name: String): Vendor? =
        vendors.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

    /**
     * Finds an existing filament for [spec] under [vendorId]: same material, colour and diameter,
     * then — because "PLA Basic Black" and "PLA Matte Charcoal" are both PLA 000000 1.75 —
     * the same Bambu material id, else the same product name, else an unnamed filament.
     */
    fun matchFilament(filaments: List<Filament>, spec: NewSpoolSpec, vendorId: Long?): Filament? {
        if (vendorId == null) return null
        val candidates = filaments.filter { f ->
            f.vendor?.id == vendorId &&
                f.material.equals(spec.material.trim(), ignoreCase = true) &&
                f.colorHex.equals(spec.colorHex.removePrefix("#"), ignoreCase = true) &&
                abs(f.diameter - spec.diameterMm) < 0.01
        }
        spec.bambuMaterialId?.let { id ->
            candidates.firstOrNull { it.extraValue(ExtraKeys.FILAMENT_MATERIAL_ID) == id }?.let { return it }
        }
        candidates.firstOrNull { it.name?.trim().equals(spec.filamentName.trim(), ignoreCase = true) }?.let { return it }
        return candidates.firstOrNull { it.name.isNullOrBlank() }
    }

    private fun preferred(spools: List<Spool>): Spool? =
        spools.sortedWith(compareBy<Spool> { it.archived }.thenByDescending { it.id }).firstOrNull()
}
