package com.bambu.nfc.data.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic Bambu tag blocks laid out per the Bambu-Research-Group RFID tag guide. */
class BambuTagParserTest {

    private val uid = byteArrayOf(0x75, 0x88.toByte(), 0x6B, 0x1D)
    private val trayUuid = "FE0F8D897AA94CB1B1E3DDC6862CFFE0"

    private fun ascii(s: String) = ByteArray(16).also { s.toByteArray(Charsets.US_ASCII).copyInto(it) }

    private fun blocks(): Map<Int, ByteArray> {
        val block1 = ByteArray(16).also { "A01-K0".toByteArray().copyInto(it, 0); "GFA01".toByteArray().copyInto(it, 8) }
        val block5 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x00); put(0xAE.toByte()); put(0x42); put(0xFF.toByte()) // RGBA 00AE42FF
            putShort(1000)                                              // weight g
            putDouble(1.75)                                              // diameter mm
        }.array()
        val block6 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(55); putShort(8); putShort(1); putShort(60); putShort(230); putShort(190)
        }.array()
        val block14 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(0); putShort(330) }.array()
        return mapOf(
            1 to block1,
            2 to ascii("PLA"),
            4 to ascii("PLA Basic"),
            5 to block5,
            6 to block6,
            9 to trayUuid.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            12 to ascii("2024_03_15_10_22"),
            14 to block14,
        )
    }

    @Test
    fun `parses filament fields from blocks`() {
        val f = BambuTagParser().parse(uid, blocks())
        assertEquals("75886B1D", f.tagUid)
        assertEquals("GFA01", f.materialId)
        assertEquals("PLA", f.filamentType)
        assertEquals("PLA Basic", f.detailedType)
        assertEquals(0xFF00AE42.toInt(), f.colorRgba)
        assertEquals(1000, f.weightGrams)
        assertEquals(1.75f, f.diameterMm, 0.0001f)
        assertEquals(55, f.dryingTempC)
        assertEquals(8, f.dryingTimeHours)
        assertEquals(60, f.bedTempC)
        assertEquals(230, f.maxHotendTempC)
        assertEquals(190, f.minHotendTempC)
        assertEquals("2024-03-15", f.productionDate)
        assertEquals(330, f.lengthMeters)
    }

    @Test
    fun `missing blocks fall back to defaults`() {
        val f = BambuTagParser().parse(uid, emptyMap())
        assertEquals("Unknown", f.filamentType)
        assertEquals(1.75f, f.diameterMm, 0.0001f)
        assertEquals(0, f.weightGrams)
    }

    @Test
    fun `tray uuid is the hex of block 9`() {
        assertEquals(trayUuid, BambuTrayUuid.fromBlocks(blocks()))
    }

    @Test
    fun `tray uuid is null when block 9 is missing or blank`() {
        assertNull(BambuTrayUuid.fromBlocks(emptyMap()))
        assertNull(BambuTrayUuid.fromBlocks(mapOf(9 to ByteArray(16))))
    }
}
