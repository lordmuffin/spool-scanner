package com.bambu.nfc.data.nfc

/** What a scanned Spoolman code (QR label, NFC sticker or App Link) points at. */
sealed interface SpoolmanCode {
    val id: Long
    data class Spool(override val id: Long) : SpoolmanCode
    data class Filament(override val id: Long) : SpoolmanCode
}

/**
 * Spoolman's label codes, mirroring its own parser (client_v2/src/lib/utils/spoolCode.ts):
 *
 * - scheme: `WEB+SPOOLMAN:S-<id>` (spool) / `WEB+SPOOLMAN:F-<id>` (filament), any case. Spoolman
 *   0.26.1 prints the upper-case form by default. Labels and stickers made by earlier versions of
 *   this app carry it too, so it's still accepted everywhere (in-app scanner, NFC).
 * - url: `http(s)://<host>[/<base path>]/spool/show/<id>` or `/filament/show/<id>`.
 *
 * New labels and stickers use the url form ([forSpool]): the stock camera only offers to open
 * http(s) links, Android App Links route them into this app, and a phone without the app lands on
 * the spool's page in Spoolman's web UI.
 */
object SpoolUri {
    const val LEGACY_PREFIX = "WEB+SPOOLMAN:S-"

    private val schemeForm = Regex("^web\\+spoolman:([sf])-([0-9]+)$", RegexOption.IGNORE_CASE)
    // Host, optional base path, /spool|filament/show/<id>, optional trailing slash, query or fragment.
    private val urlForm = Regex(
        "^https?://[^/?#]+(?:/[^/?#]+)*/(spool|filament)/show/([0-9]+)/?(?:[?#].*)?$",
        RegexOption.IGNORE_CASE,
    )

    /** `<Spoolman base URL>/spool/show/<id>`; [baseUrl] may or may not end with a slash. */
    fun forSpool(baseUrl: String, id: Long): String = "${baseUrl.trim().trimEnd('/')}/spool/show/$id"

    /** The compact form Spoolman 0.26.1 prints by default. Only parsed now, no longer written. */
    fun legacyForSpool(id: Long): String = "$LEGACY_PREFIX$id"

    /** The Spoolman entity [value] points at, or null when it isn't a Spoolman code. */
    fun parseCode(value: String): SpoolmanCode? {
        val text = value.trim()
        schemeForm.matchEntire(text)?.let { m ->
            val id = m.groupValues[2].toLongOrNull() ?: return null
            return if (m.groupValues[1].equals("f", ignoreCase = true)) SpoolmanCode.Filament(id) else SpoolmanCode.Spool(id)
        }
        urlForm.matchEntire(text)?.let { m ->
            val id = m.groupValues[2].toLongOrNull() ?: return null
            return if (m.groupValues[1].equals("filament", ignoreCase = true)) SpoolmanCode.Filament(id) else SpoolmanCode.Spool(id)
        }
        return null
    }

    /** The Spoolman spool id encoded in [value], or null if it is not a spool code. */
    fun parse(value: String): Long? = (parseCode(value) as? SpoolmanCode.Spool)?.id
}
