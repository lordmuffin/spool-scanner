package com.bambu.nfc.data.nfc

/**
 * The spool identifier Spoolman puts in its own label QR codes, reused as the NDEF URI on
 * NTAG215 stickers so a sticker, a Spoolman QR label and a labelito label all carry the same string.
 *
 * Spoolman 0.26.1 generates `WEB+SPOOLMAN:S-<id>` in upper case (client/src/pages/printing/
 * spoolQrCodePrintingDialog.tsx; upper case keeps a QR code in alphanumeric mode) and its scanner
 * accepts it case-insensitively, plus `http(s)://<host>/spool/show/<id>` when the "use HTTP URL"
 * print option is on (client/src/components/qrCodeScanner.tsx). We write the default form and read both.
 */
object SpoolUri {
    const val PREFIX = "WEB+SPOOLMAN:S-"

    private val schemeForm = Regex("^web\\+spoolman:s-([0-9]+)$", RegexOption.IGNORE_CASE)
    // Spoolman's scanner requires the URL to be host + /spool/show/<id>; we also tolerate a
    // base path (Spoolman behind a sub-path) and a trailing slash.
    private val urlForm = Regex("^https?://[^/]+(?:/[^?#]*)?/spool/show/([0-9]+)/?$", RegexOption.IGNORE_CASE)

    fun forSpool(id: Long): String = "$PREFIX$id"

    /** Returns the Spoolman spool id encoded in [value], or null if it is not a Spoolman spool code. */
    fun parse(value: String): Long? {
        val trimmed = value.trim()
        val match = schemeForm.matchEntire(trimmed) ?: urlForm.matchEntire(trimmed) ?: return null
        return match.groupValues[1].toLongOrNull()
    }
}
