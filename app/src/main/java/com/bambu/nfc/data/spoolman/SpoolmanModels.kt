package com.bambu.nfc.data.spoolman

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// Spoolman 0.26.1 REST shapes (spoolman/api/v1/*.py). Property names are mapped to snake_case by
// SpoolmanJson's naming strategy; unknown keys are ignored so newer servers keep working.

@Serializable
data class Vendor(
    val id: Long,
    val name: String,
    val extra: Map<String, String> = emptyMap(),
)

@Serializable
data class Filament(
    val id: Long,
    val name: String? = null,
    val vendor: Vendor? = null,
    val material: String? = null,
    val density: Double = 1.24,
    val diameter: Double = 1.75,
    val weight: Double? = null,
    val spoolWeight: Double? = null,
    val colorHex: String? = null,
    val settingsExtruderTemp: Int? = null,
    val settingsBedTemp: Int? = null,
    val extra: Map<String, String> = emptyMap(),
)

@Serializable
data class Spool(
    val id: Long,
    val filament: Filament,
    val remainingWeight: Double? = null,
    val initialWeight: Double? = null,
    val spoolWeight: Double? = null,
    val usedWeight: Double = 0.0,
    val location: String? = null,
    val comment: String? = null,
    val archived: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
) {
    /** Empty-spool weight Spoolman's measure endpoint will subtract (spool, else filament). */
    val effectiveSpoolWeight: Double? get() = spoolWeight ?: filament.spoolWeight
}

@Serializable
data class ExtraField(
    val key: String,
    val name: String,
    val fieldType: String,
    val entityType: String? = null,
)

@Serializable
data class ExtraFieldParams(val name: String, val fieldType: String = "text", val order: Int = 0)

@Serializable
data class VendorCreate(val name: String, val comment: String? = null)

@Serializable
data class FilamentCreate(
    val name: String,
    val vendorId: Long,
    val material: String,
    val density: Double,
    val diameter: Double,
    val weight: Double? = null,
    val spoolWeight: Double? = null,
    val colorHex: String? = null,
    val settingsExtruderTemp: Int? = null,
    val settingsBedTemp: Int? = null,
    val extra: Map<String, String>? = null,
)

@Serializable
data class FilamentPatch(val extra: Map<String, String>)

@Serializable
data class SpoolCreate(
    val filamentId: Long,
    val initialWeight: Double,
    val spoolWeight: Double? = null,
    val location: String? = null,
    val comment: String? = null,
    val extra: Map<String, String>? = null,
)

@Serializable
data class SpoolPatch(
    val location: String? = null,
    val archived: Boolean? = null,
    val extra: Map<String, String>? = null,
)

@Serializable
data class SpoolUse(val useWeight: Double)

@Serializable
data class SpoolMeasure(val weight: Double)

/**
 * Spoolman stores extra-field values as JSON-encoded strings: a text field holding `abc` is sent
 * and returned as the 4-character string `"abc"` (quotes included).
 */
object ExtraValue {
    fun encode(value: String): String = Json.encodeToString(String.serializer(), value)

    fun decode(raw: String?): String? {
        if (raw == null) return null
        return runCatching { Json.decodeFromString(String.serializer(), raw) }.getOrElse { raw }
    }
}

fun Spool.extraValue(key: String): String? = ExtraValue.decode(extra[key])
fun Filament.extraValue(key: String): String? = ExtraValue.decode(extra[key])

object ExtraKeys {
    // Created by this app on startup if missing.
    const val SPOOL_TRAY_UUID = "bambu_tray_uuid"
    const val SPOOL_NFC_UID = "nfc_uid"
    const val FILAMENT_MATERIAL_ID = "bambu_material_id"

    // Bambuddy's fields: read for matching, written only when they already exist on the server.
    const val BAMBUDDY_TAG = "tag"
    const val BAMBUDDY_COLOR_NAME = "bambu_color_name"

    data class Required(val entity: String, val key: String, val name: String)

    val REQUIRED = listOf(
        Required("spool", SPOOL_TRAY_UUID, "Bambu tray UUID"),
        Required("spool", SPOOL_NFC_UID, "NFC tag UID"),
        Required("filament", FILAMENT_MATERIAL_ID, "Bambu material ID"),
    )
}
