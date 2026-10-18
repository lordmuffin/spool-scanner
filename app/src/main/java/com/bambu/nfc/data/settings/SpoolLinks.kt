package com.bambu.nfc.data.settings

import com.bambu.nfc.data.nfc.SpoolUri

/** Builds the link a label QR or NFC sticker carries, from the Spoolman base URL in Settings. */
class SpoolLinks(private val spoolmanBaseUrl: () -> String) {
    fun forSpool(id: Long): String = SpoolUri.forSpool(spoolmanBaseUrl(), id)
}
