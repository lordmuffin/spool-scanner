package com.bambu.nfc.data.nfc

import com.bambu.nfc.util.toHex

/**
 * The spool-wide identifier on a Bambu Lab tag: block 9 (sector 2, block 1), 16 raw bytes.
 * Both tags on one spool carry the same value (their UIDs differ), and it is what the AMS reports
 * as `tray_uuid` — a 32-character upper-case hex string. Bambuddy stores that same string in the
 * Spoolman spool extra field `tag`, so matching on hex lets us find spools Bambuddy created.
 *
 * BambuTagParser.trayUid decodes this block as ASCII, which mangles the binary UUID, so we derive
 * the hex form from the raw block here instead of changing the parser.
 */
object BambuTrayUuid {
    fun fromBlocks(blocks: Map<Int, ByteArray>): String? {
        val block = blocks[9] ?: return null
        if (block.size < 16 || block.all { it == 0.toByte() }) return null
        return block.copyOf(16).toHex()
    }
}
