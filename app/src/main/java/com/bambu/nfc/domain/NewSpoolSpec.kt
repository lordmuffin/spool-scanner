package com.bambu.nfc.domain

import com.bambu.nfc.data.spoolman.ExtraKeys
import com.bambu.nfc.data.spoolman.Filament
import com.bambu.nfc.data.spoolman.extraValue
import com.bambu.nfc.domain.model.FilamentData
import com.bambu.nfc.util.toHexColorString

/**
 * Everything needed to create (vendor →) filament → spool in Spoolman. Built from a Bambu tag,
 * or typed in for a third-party spool, and shown as an editable form before anything is created.
 */
data class NewSpoolSpec(
    val vendorName: String,
    val filamentName: String,
    val material: String,
    /** `RRGGBB`, upper case, no `#` — Spoolman's color_hex format. */
    val colorHex: String,
    val colorName: String? = null,
    val diameterMm: Double = 1.75,
    val densityGcm3: Double = MaterialDefaults.densityFor(material),
    /** Net filament weight, grams. Used as the filament's weight and the spool's initial_weight. */
    val netWeightG: Double = 1000.0,
    val spoolWeightG: Double? = null,
    val extruderTempC: Int? = null,
    val bedTempC: Int? = null,
    val location: String? = null,
    val bambuMaterialId: String? = null,
    val bambuTrayUuid: String? = null,
    val nfcUid: String? = null,
    /** Use this existing Spoolman filament instead of matching/creating one (from a filament QR code). */
    val filamentId: Long? = null,
) {
    companion object {
        const val BAMBU_VENDOR = "Bambu Lab"

        fun fromBambuTag(filament: FilamentData, trayUuid: String?): NewSpoolSpec {
            val hex = filament.colorRgba.toHexColorString().removePrefix("#")
            val name = filament.detailedType.ifBlank { filament.filamentType }
            return NewSpoolSpec(
                vendorName = BAMBU_VENDOR,
                filamentName = name,
                material = filament.filamentType,
                colorHex = hex,
                colorName = BambuColors.nameFor(hex, name),
                diameterMm = filament.diameterMm.toDouble().takeIf { it > 0 }?.let { roundTo(it, 2) } ?: 1.75,
                // The tag has no density; fall back to the material's typical value (editable in the form).
                densityGcm3 = MaterialDefaults.densityFor(filament.filamentType),
                netWeightG = filament.weightGrams.takeIf { it > 0 }?.toDouble() ?: 1000.0,
                extruderTempC = midpoint(filament.minHotendTempC, filament.maxHotendTempC),
                bedTempC = filament.bedTempC.takeIf { it > 0 },
                bambuMaterialId = filament.materialId.ifBlank { null },
                bambuTrayUuid = trayUuid,
                nfcUid = filament.tagUid,
            )
        }

        /** Create-form prefill for a filament QR code: a new spool of an existing Spoolman filament. */
        fun fromFilament(filament: Filament): NewSpoolSpec {
            val hex = filament.colorHex?.removePrefix("#")?.uppercase().orEmpty()
            val name = filament.name?.trim().orEmpty()
            val material = filament.material?.trim().orEmpty()
            return NewSpoolSpec(
                vendorName = filament.vendor?.name.orEmpty(),
                filamentName = name,
                material = material,
                colorHex = hex,
                colorName = hex.takeIf { it.length == 6 }?.let { BambuColors.nameFor(it, name) },
                diameterMm = filament.diameter,
                densityGcm3 = filament.density,
                netWeightG = filament.weight?.takeIf { it > 0 } ?: 1000.0,
                spoolWeightG = filament.spoolWeight,
                extruderTempC = filament.settingsExtruderTemp,
                bedTempC = filament.settingsBedTemp,
                bambuMaterialId = filament.extraValue(ExtraKeys.FILAMENT_MATERIAL_ID),
                filamentId = filament.id,
            )
        }

        /** Midpoint of the tag's nozzle range rounded to 5 °C, e.g. 190–230 → 210. */
        internal fun midpoint(min: Int, max: Int): Int? = when {
            min > 0 && max > 0 -> (((min + max) / 2.0) / 5.0).let { Math.round(it).toInt() * 5 }
            max > 0 -> max
            min > 0 -> min
            else -> null
        }

        private fun roundTo(v: Double, places: Int): Double {
            val f = Math.pow(10.0, places.toDouble())
            return Math.round(v * f) / f
        }
    }
}

object MaterialDefaults {
    private val density = mapOf(
        "PLA" to 1.24, "PETG" to 1.27, "ABS" to 1.04, "ASA" to 1.07, "TPU" to 1.21,
        "PA" to 1.14, "PC" to 1.20, "PVA" to 1.23, "HIPS" to 1.04, "PET" to 1.38,
    )

    fun densityFor(material: String): Double = density[material.trim().uppercase()] ?: 1.24
}
