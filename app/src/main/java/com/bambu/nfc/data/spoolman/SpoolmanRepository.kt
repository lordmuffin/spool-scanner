package com.bambu.nfc.data.spoolman

import com.bambu.nfc.domain.NewSpoolSpec
import com.bambu.nfc.domain.SpoolMatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spoolman is the source of truth; this wraps its API with the app's lookup/create rules. */
class SpoolmanRepository(private val provider: SpoolmanApiProvider) {

    private val fieldsMutex = Mutex()
    // Base URL → spool extra-field keys known to exist there. Reset when the URL changes.
    private var knownSpoolFields: Pair<String, Set<String>>? = null

    private val api get() = provider.api()

    suspend fun info() = api.info()

    /**
     * Idempotently creates the extra fields this app writes: GET each entity's fields and POST only
     * the missing ones. Returns the keys it created. Safe to call on every start.
     */
    suspend fun ensureExtraFields(): List<String> = fieldsMutex.withLock {
        val created = mutableListOf<String>()
        val byEntity = ExtraKeys.REQUIRED.groupBy { it.entity }
        var spoolKeys = emptySet<String>()
        for ((entity, required) in byEntity) {
            var existing = api.fields(entity).map { it.key }.toSet()
            for (field in required) {
                if (field.key !in existing) {
                    existing = api.addField(entity, field.key, ExtraFieldParams(name = field.name)).map { it.key }.toSet()
                    created += "$entity.${field.key}"
                }
            }
            if (entity == "spool") spoolKeys = existing
        }
        knownSpoolFields = provider.currentBaseUrl() to spoolKeys
        created
    }

    private suspend fun spoolFieldKeys(): Set<String> {
        val url = provider.currentBaseUrl()
        knownSpoolFields?.let { (u, keys) -> if (u == url) return keys }
        ensureExtraFields()
        return knownSpoolFields?.second.orEmpty()
    }

    suspend fun listSpools(includeArchived: Boolean = true): List<Spool> = api.spools(includeArchived)

    suspend fun getSpool(id: Long): Spool? = try {
        api.spool(id)
    } catch (e: retrofit2.HttpException) {
        if (e.code() == 404) null else throw e
    }

    /**
     * Looks a Bambu tag up, then records what the spool didn't know yet: this tag's UID, and the
     * tray UUID when the spool was linked by UID alone (e.g. from a phone that can't decode Bambu tags).
     */
    suspend fun findBambuSpool(trayUuid: String?, tagUid: String): Spool? {
        val spool = SpoolMatcher.findBambuSpool(listSpools(), trayUuid, tagUid) ?: return null
        return learn(spool, tagUid, trayUuid)
    }

    suspend fun findByNfcUid(tagUid: String): Spool? = SpoolMatcher.findByNfcUid(listSpools(), tagUid)

    /** Appends [tagUid] to the spool's `nfc_uid` list (no-op when already there). */
    suspend fun learnNfcUid(spool: Spool, tagUid: String): Spool = learn(spool, tagUid, trayUuid = null)

    private suspend fun learn(spool: Spool, tagUid: String, trayUuid: String?): Spool {
        val keys = spoolFieldKeys()
        val patch = buildMap {
            SpoolMatcher.nfcUidsWith(spool, tagUid)?.let { put(ExtraKeys.SPOOL_NFC_UID, it) }
            if (trayUuid != null && spool.extraValue(ExtraKeys.SPOOL_TRAY_UUID).isNullOrBlank()) {
                put(ExtraKeys.SPOOL_TRAY_UUID, trayUuid)
            }
        }.filterKeys { it in keys }
        if (patch.isEmpty()) return spool
        return api.patchSpool(spool.id, SpoolPatch(extra = patch.mapValues { ExtraValue.encode(it.value) }))
    }

    /** Match or create vendor → filament, then create the spool. */
    suspend fun createSpool(spec: NewSpoolSpec): Spool {
        val spoolKeys = spoolFieldKeys()

        val vendor = SpoolMatcher.matchVendor(api.vendors(), spec.vendorName)
            ?: api.createVendor(VendorCreate(name = spec.vendorName.trim()))

        val filament = SpoolMatcher.matchFilament(api.filaments(), spec, vendor.id)
            ?.let { backfillMaterialId(it, spec) }
            ?: api.createFilament(
                FilamentCreate(
                    name = spec.filamentName.trim(),
                    vendorId = vendor.id,
                    material = spec.material.trim(),
                    density = spec.densityGcm3,
                    diameter = spec.diameterMm,
                    weight = spec.netWeightG,
                    spoolWeight = spec.spoolWeightG,
                    colorHex = spec.colorHex.removePrefix("#").uppercase(),
                    settingsExtruderTemp = spec.extruderTempC,
                    settingsBedTemp = spec.bedTempC,
                    extra = spec.bambuMaterialId?.let { mapOf(ExtraKeys.FILAMENT_MATERIAL_ID to ExtraValue.encode(it)) },
                )
            )

        // Only fields that exist on the server: Spoolman rejects unknown extra keys. Bambuddy's `tag`
        // lets it recognise the spool in the AMS rather than adding a duplicate.
        val extra = buildMap {
            spec.bambuTrayUuid?.let {
                put(ExtraKeys.SPOOL_TRAY_UUID, it)
                put(ExtraKeys.BAMBUDDY_TAG, it)
            }
            spec.nfcUid?.let { put(ExtraKeys.SPOOL_NFC_UID, it.uppercase()) }
            spec.colorName?.let { put(ExtraKeys.BAMBUDDY_COLOR_NAME, it) }
        }.filterKeys { it in spoolKeys }.mapValues { ExtraValue.encode(it.value) }

        return api.createSpool(
            SpoolCreate(
                filamentId = filament.id,
                initialWeight = spec.netWeightG,
                spoolWeight = spec.spoolWeightG,
                location = spec.location?.ifBlank { null },
                comment = "Created by Spool Scanner",
                extra = extra.ifEmpty { null },
            )
        )
    }

    private suspend fun backfillMaterialId(filament: Filament, spec: NewSpoolSpec): Filament {
        val id = spec.bambuMaterialId ?: return filament
        if (filament.extraValue(ExtraKeys.FILAMENT_MATERIAL_ID) != null) return filament
        return runCatching {
            api.patchFilament(filament.id, FilamentPatch(mapOf(ExtraKeys.FILAMENT_MATERIAL_ID to ExtraValue.encode(id))))
        }.getOrDefault(filament)
    }

    /** Links an existing spool to a tag UID (for stickers, or Bambu tags this phone can't decode). */
    suspend fun linkNfcUid(spoolId: Long, tagUid: String): Spool {
        val spool = api.spool(spoolId)
        return learnNfcUid(spool, tagUid)
    }

    /** Records the current gross weight (spool + filament); Spoolman subtracts the empty-spool weight. */
    suspend fun measure(spoolId: Long, grossWeightG: Double): Spool = api.measure(spoolId, SpoolMeasure(grossWeightG))

    suspend fun use(spoolId: Long, grams: Double): Spool = api.use(spoolId, SpoolUse(grams))

    suspend fun setLocation(spoolId: Long, location: String): Spool =
        api.patchSpool(spoolId, SpoolPatch(location = location.trim()))

    suspend fun setArchived(spoolId: Long, archived: Boolean): Spool =
        api.patchSpool(spoolId, SpoolPatch(archived = archived))
}
