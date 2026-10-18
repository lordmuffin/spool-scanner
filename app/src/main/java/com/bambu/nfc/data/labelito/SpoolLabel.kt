package com.bambu.nfc.data.labelito

import com.bambu.nfc.data.nfc.SpoolUri
import com.bambu.nfc.data.spoolman.ExtraKeys
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.extraValue
import com.bambu.nfc.domain.BambuColors

/** Fields for labelito's `spool-label` template (defined in np-home-homelab). */
object SpoolLabel {

    fun fields(spool: Spool): Map<String, String> {
        val f = spool.filament
        return mapOf(
            "material" to materialLine(f.material, f.name),
            "color" to colorLine(spool),
            "vendor" to (f.vendor?.name ?: ""),
            "spool_id" to "#${spool.id}",
            "qr" to SpoolUri.forSpool(spool.id),
        )
    }

    /** "PLA Matte" rather than just "PLA" when the filament name already carries the material. */
    internal fun materialLine(material: String?, name: String?): String {
        val m = material?.trim().orEmpty()
        val n = name?.trim().orEmpty()
        return when {
            n.isEmpty() -> m
            m.isEmpty() || n.contains(m, ignoreCase = true) -> n
            else -> "$m $n"
        }
    }

    internal fun colorLine(spool: Spool): String {
        spool.extraValue(ExtraKeys.BAMBUDDY_COLOR_NAME)?.takeIf { it.isNotBlank() }?.let { return it }
        val hex = spool.filament.colorHex ?: return ""
        return BambuColors.nameFor(hex, spool.filament.name) ?: "#${hex.uppercase()}"
    }
}
