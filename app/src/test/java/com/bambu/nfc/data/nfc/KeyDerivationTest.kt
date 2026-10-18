package com.bambu.nfc.data.nfc

import com.bambu.nfc.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected keys computed independently with Python's hmac/hashlib HKDF-SHA256 (RFC 5869). */
class KeyDerivationTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `derives per-sector keys from a 4-byte uid`() {
        val uid = hex("75886B1D")
        assertEquals("6E5B0EC6EF7C", KeyDerivation.deriveKeyForSector(uid, 0).toHex())
        assertEquals("4CE96076285F", KeyDerivation.deriveKeyForSector(uid, 1).toHex())
        assertEquals("D9805EEC7045", KeyDerivation.deriveKeyForSector(uid, 15).toHex())
    }

    @Test
    fun `uses only the first four bytes of a longer uid`() {
        val uid = hex("04A1B2C3D4E5F6")
        assertEquals("2235AEC2322C", KeyDerivation.deriveKeyForSector(uid, 0).toHex())
        assertEquals("BF605B2807A0", KeyDerivation.deriveKeyForSector(uid, 1).toHex())
        assertEquals("FFA23477E0D9", KeyDerivation.deriveKeyForSector(uid, 15).toHex())
    }
}
