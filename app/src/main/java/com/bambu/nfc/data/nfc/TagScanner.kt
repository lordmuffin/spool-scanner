package com.bambu.nfc.data.nfc

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log
import com.bambu.nfc.domain.model.FilamentData
import com.bambu.nfc.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What a tapped tag turned out to be, before any Spoolman lookup. */
sealed interface TagRead {
    val uid: String

    /** A genuine Bambu Lab tag, decoded. [trayUuid] is shared by both tags on the spool. */
    data class Bambu(override val uid: String, val filament: FilamentData, val trayUuid: String?) : TagRead

    /** An NDEF tag (our NTAG215 stickers). [spoolId] is set when it carries a Spoolman spool URI. */
    data class Sticker(
        override val uid: String,
        val uris: List<String>,
        val spoolId: Long?,
        val writable: Boolean,
    ) : TagRead

    /**
     * A tag we can't decode. [mifareClassic] means it looks like MIFARE Classic (likely a Bambu tag)
     * but it couldn't be read: either this phone's NFC stack doesn't expose the MifareClassic tech,
     * or sector authentication failed. Which phones can read Bambu tags varies by NFC controller, and
     * reports for Pixels differ by model, so check the logged tech list rather than assuming.
     * Lookup then falls back to a UID recorded in Spoolman's `nfc_uid`.
     */
    data class Unreadable(override val uid: String, val reason: String, val mifareClassic: Boolean) : TagRead
}

@Singleton
class TagScanner @Inject constructor(
    private val bambuReader: BambuTagReader,
    private val bambuParser: BambuTagParser,
) {

    private companion object {
        const val TAG = "TagScanner"
    }

    suspend fun read(tag: Tag): TagRead {
        val uid = tag.id.toHex()
        val techs = tag.techList.toSet()
        logTag(tag, uid)

        if (MifareClassic::class.java.name in techs) {
            return when (val r = bambuReader.readTag(tag)) {
                is BambuTagReader.ReadResult.Success -> TagRead.Bambu(
                    uid = uid,
                    filament = bambuParser.parse(r.uid, r.blocks),
                    trayUuid = BambuTrayUuid.fromBlocks(r.blocks),
                )
                is BambuTagReader.ReadResult.Error -> TagRead.Unreadable(uid, r.message, mifareClassic = true)
            }
        }

        if (Ndef::class.java.name in techs) return readNdef(tag, uid)
        if (NdefFormatable::class.java.name in techs) {
            return TagRead.Sticker(uid, uris = emptyList(), spoolId = null, writable = true)
        }

        // MIFARE Classic answers anticollision with SAK bit 0x08. When the NFC stack doesn't
        // support it, the tag shows up with only the NfcA tech.
        val sak = NfcA.get(tag)?.sak?.toInt() ?: 0
        val looksClassic = sak and 0x08 != 0
        return TagRead.Unreadable(
            uid,
            if (looksClassic) {
                "This phone can't read MIFARE Classic tags (Bambu Lab RFID). The spool can still be " +
                    "recognised by its tag UID once it's linked in Spoolman."
            } else {
                "Unsupported tag (${techs.joinToString { it.substringAfterLast('.') }})."
            },
            mifareClassic = looksClassic,
        )
    }

    /**
     * Diagnostics for "the phone doesn't react / can't read this tag". Not gated on DEBUG so a
     * release build can be checked with `adb logcat -s TagScanner`. Tag UIDs aren't secret.
     */
    private fun logTag(tag: Tag, uid: String) {
        val nfcA = NfcA.get(tag)
        val sak = nfcA?.sak?.toInt()?.let { "0x%02X".format(it) } ?: "n/a"
        val atqa = nfcA?.atqa?.toHex() ?: "n/a"
        Log.d(TAG, "Tag uid=$uid (${tag.id.size} bytes) sak=$sak atqa=$atqa techs=${tag.techList.joinToString { it.substringAfterLast('.') }}")
    }

    private suspend fun readNdef(tag: Tag, uid: String): TagRead = withContext(Dispatchers.IO) {
        val ndef = Ndef.get(tag)
        val message: NdefMessage? = ndef.cachedNdefMessage ?: try {
            ndef.connect()
            ndef.ndefMessage
        } catch (e: Exception) {
            null
        } finally {
            runCatching { ndef.close() }
        }
        val uris = message?.let { runCatching { NdefCodec.decodeUris(it.toByteArray()) }.getOrDefault(emptyList()) }.orEmpty()
        TagRead.Sticker(
            uid = uid,
            uris = uris,
            spoolId = uris.firstNotNullOfOrNull { SpoolUri.parse(it) },
            writable = ndef.isWritable,
        )
    }

    /** Writes `WEB+SPOOLMAN:S-<id>` as the tag's only NDEF record. Never locks the tag. */
    suspend fun writeSpoolUri(tag: Tag, spoolId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val message = NdefMessage(NdefCodec.encodeUriMessage(SpoolUri.forSpool(spoolId)))
        runCatching {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                try {
                    ndef.connect()
                    if (!ndef.isWritable) throw IOException("This tag is read-only.")
                    if (ndef.maxSize < message.toByteArray().size) throw IOException("Tag too small (${ndef.maxSize} bytes).")
                    ndef.writeNdefMessage(message)
                } finally {
                    runCatching { ndef.close() }
                }
            } else {
                val formatable = NdefFormatable.get(tag) ?: throw IOException("This tag can't hold NDEF data. Use an NTAG215 sticker.")
                try {
                    formatable.connect()
                    formatable.format(message)
                } finally {
                    runCatching { formatable.close() }
                }
            }
        }.recoverCatching { e ->
            throw IOException(
                if (e is android.nfc.TagLostException) "Tag moved away while writing. Hold the phone still and try again."
                else e.message ?: "Write failed", e
            )
        }
    }
}
