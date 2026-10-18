package com.bambu.nfc.data.nfc

import java.io.ByteArrayOutputStream

/**
 * Minimal NDEF message codec for URI records, written against raw bytes rather than
 * android.nfc.NdefMessage so it can be unit tested on the JVM. On a device the bytes come from
 * `NdefMessage.toByteArray()` and go back through `NdefMessage(bytes)`.
 *
 * Record header byte: MB(0x80) ME(0x40) CF(0x20) SR(0x10) IL(0x08) TNF(0x07).
 * A well-known "U" record's payload is one URI-identifier-code byte followed by the UTF-8 rest
 * (NFC Forum URI RTD); an absolute-URI record (TNF 0x03) carries the URI as its type.
 */
object NdefCodec {

    private const val TNF_WELL_KNOWN = 0x01
    private const val TNF_ABSOLUTE_URI = 0x03
    private val TYPE_URI = byteArrayOf('U'.code.toByte())

    // NFC Forum URI RTD identifier codes, index = code.
    private val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
        "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:",
        "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://",
        "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
        "urn:nfc:"
    )

    data class Record(val tnf: Int, val type: ByteArray, val id: ByteArray, val payload: ByteArray)

    class MalformedNdefException(message: String) : Exception(message)

    /** Encodes a single-record NDEF message holding [uri] as a well-known URI record. */
    fun encodeUriMessage(uri: String): ByteArray {
        val (code, rest) = abbreviate(uri)
        val restBytes = rest.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + restBytes.size)
        payload[0] = code.toByte()
        restBytes.copyInto(payload, 1)
        return encodeRecord(TNF_WELL_KNOWN, TYPE_URI, payload)
    }

    /** Every URI found in [message], in record order. Non-URI records are skipped. */
    fun decodeUris(message: ByteArray): List<String> =
        decodeRecords(message).mapNotNull { record ->
            when {
                record.tnf == TNF_WELL_KNOWN && record.type.contentEquals(TYPE_URI) -> decodeUriPayload(record.payload)
                record.tnf == TNF_ABSOLUTE_URI -> String(record.type, Charsets.UTF_8)
                else -> null
            }
        }

    fun decodeRecords(message: ByteArray): List<Record> {
        val records = mutableListOf<Record>()
        var pos = 0
        while (pos < message.size) {
            val header = message[pos].toInt() and 0xFF
            pos++
            val tnf = header and 0x07
            val shortRecord = header and 0x10 != 0
            val hasId = header and 0x08 != 0
            val messageEnd = header and 0x40 != 0
            if (header and 0x20 != 0) throw MalformedNdefException("Chunked NDEF records are not supported")

            val typeLength = byteAt(message, pos++)
            val payloadLength = if (shortRecord) {
                byteAt(message, pos++).toLong()
            } else {
                if (pos + 4 > message.size) throw MalformedNdefException("Truncated payload length")
                val len = ((byteAt(message, pos).toLong() shl 24) or (byteAt(message, pos + 1).toLong() shl 16) or
                    (byteAt(message, pos + 2).toLong() shl 8) or byteAt(message, pos + 3).toLong())
                pos += 4
                len
            }
            val idLength = if (hasId) byteAt(message, pos++) else 0

            val end = pos.toLong() + typeLength + idLength + payloadLength
            if (end > message.size) throw MalformedNdefException("Record runs past end of message")

            val type = message.copyOfRange(pos, pos + typeLength); pos += typeLength
            val id = message.copyOfRange(pos, pos + idLength); pos += idLength
            val payload = message.copyOfRange(pos, pos + payloadLength.toInt()); pos += payloadLength.toInt()
            records += Record(tnf, type, id, payload)
            if (messageEnd) break
        }
        return records
    }

    private fun decodeUriPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val code = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrNull(code) ?: ""
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    private fun abbreviate(uri: String): Pair<Int, String> {
        // Longest matching prefix wins; code 0 (no abbreviation) for our web+spoolman scheme.
        var best = 0
        for (code in 1 until URI_PREFIXES.size) {
            val p = URI_PREFIXES[code]
            if (uri.startsWith(p) && p.length > URI_PREFIXES[best].length) best = code
        }
        return best to uri.substring(URI_PREFIXES[best].length)
    }

    private fun encodeRecord(tnf: Int, type: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val short = payload.size < 256
        var header = 0x80 or 0x40 or tnf // MB + ME: single-record message
        if (short) header = header or 0x10
        out.write(header)
        out.write(type.size)
        if (short) {
            out.write(payload.size)
        } else {
            out.write((payload.size ushr 24) and 0xFF)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
        }
        out.write(type)
        out.write(payload)
        return out.toByteArray()
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int {
        if (index >= bytes.size) throw MalformedNdefException("Truncated NDEF record")
        return bytes[index].toInt() and 0xFF
    }
}
