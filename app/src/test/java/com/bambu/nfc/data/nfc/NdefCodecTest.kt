package com.bambu.nfc.data.nfc

import com.bambu.nfc.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefCodecTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `encodes spool uri as a single short well-known URI record`() {
        val bytes = NdefCodec.encodeUriMessage("WEB+SPOOLMAN:S-12")
        // D1 = MB|ME|SR|TNF=1, type length 1, payload length 18, 'U', prefix code 0, then the URI.
        val expected = hex("D1 01 12 55 00") + "WEB+SPOOLMAN:S-12".toByteArray()
        assertEquals(expected.toHex(), bytes.toHex())
    }

    @Test
    fun `round-trips`() {
        for (uri in listOf(SpoolUri.forSpool("https://spoolman.lab.apj.dev", 123), SpoolUri.legacyForSpool(123))) {
            assertEquals(listOf(uri), NdefCodec.decodeUris(NdefCodec.encodeUriMessage(uri)))
        }
    }

    @Test
    fun `abbreviates and expands well-known prefixes`() {
        val bytes = NdefCodec.encodeUriMessage("https://spoolman.lab.apj.dev/spool/show/7")
        assertEquals(0x04, bytes[4].toInt()) // "https://"
        assertEquals(listOf("https://spoolman.lab.apj.dev/spool/show/7"), NdefCodec.decodeUris(bytes))
    }

    @Test
    fun `decodes long records and skips non-uri records`() {
        // Text record ("en", "hi") first, then a non-short (SR=0) URI record.
        val text = hex("91 01 05 54 02 65 6E 68 69")
        val uri = "web+spoolman:s-42".toByteArray()
        val longUri = hex("41 01 00 00 00") + byteArrayOf((uri.size + 1).toByte(), 0x55, 0x00) + uri
        assertEquals(listOf("web+spoolman:s-42"), NdefCodec.decodeUris(text + longUri))
    }

    @Test
    fun `decodes absolute-uri records`() {
        val type = "WEB+SPOOLMAN:S-9".toByteArray()
        val record = byteArrayOf(0xD3.toByte(), type.size.toByte(), 0x00) + type
        assertEquals(listOf("WEB+SPOOLMAN:S-9"), NdefCodec.decodeUris(record))
    }

    @Test
    fun `records with an id field are parsed`() {
        val record = hex("D9 01 03 01 55 41 00") + "ab".toByteArray() // IL set, id "A", payload 00 'a' 'b'
        val parsed = NdefCodec.decodeRecords(record).single()
        assertArrayEquals("A".toByteArray(), parsed.id)
        assertEquals(listOf("ab"), NdefCodec.decodeUris(record))
    }

    @Test
    fun `truncated message is rejected`() {
        val bytes = NdefCodec.encodeUriMessage("WEB+SPOOLMAN:S-12")
        val result = runCatching { NdefCodec.decodeUris(bytes.copyOf(bytes.size - 3)) }
        assertTrue(result.exceptionOrNull() is NdefCodec.MalformedNdefException)
    }

    @Test
    fun `empty message has no uris`() {
        assertEquals(emptyList<String>(), NdefCodec.decodeUris(ByteArray(0)))
    }
}
