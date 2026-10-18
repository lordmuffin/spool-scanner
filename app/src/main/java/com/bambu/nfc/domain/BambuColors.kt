package com.bambu.nfc.domain

import kotlin.math.sqrt

/**
 * Bambu Lab official colour names by hex code (ported from upstream's web dashboard table),
 * used for the label's colour line when Spoolman has no `bambu_color_name` for the spool.
 */
object BambuColors {

    private val basic = mapOf(
        // PLA Basic (official)
        "FFFFFF" to "Jade White", "000000" to "Black", "C12E1F" to "Red", "9D432C" to "Brown",
        "FF6A13" to "Orange", "6F5034" to "Cocoa Brown", "FF9016" to "Pumpkin Orange", "E4BD68" to "Gold",
        "FEC600" to "Sunflower Yellow", "847D48" to "Bronze", "F4EE2A" to "Yellow", "BECF00" to "Bright Green",
        "3F8E43" to "Mistletoe Green", "00AE42" to "Bambu Green", "00B1B7" to "Turquoise", "0086D6" to "Cyan",
        "0056B8" to "Cobalt Blue", "5B6579" to "Blue Grey", "0A2989" to "Blue", "5E43B7" to "Purple",
        "482960" to "Indigo Purple", "EC008C" to "Magenta", "F5547C" to "Hot Pink", "F55A74" to "Pink",
        "9D2235" to "Maroon Red", "545454" to "Dark Gray", "F7E6DE" to "Beige", "8E9089" to "Gray",
        "A6A9AA" to "Silver", "D1D3D5" to "Light Gray",
        // PLA Matte (official)
        "DE4343" to "Scarlet Red", "B15533" to "Terracotta", "F99963" to "Mandarin Orange", "D3B7A7" to "Latte Brown",
        "4D3324" to "Dark Chocolate", "7D6556" to "Dark Brown", "AE835B" to "Caramel", "E8DBB7" to "Desert Tan",
        "CBC6B8" to "Bone White", "F7D959" to "Lemon Yellow", "68724D" to "Dark Green", "C2E189" to "Apple Green",
        "61C680" to "Grass Green", "0078BF" to "Marine Blue", "AE96D4" to "Lilac Purple", "E8AFCF" to "Sakura Pink",
        "950051" to "Plum", "BB3D43" to "Dark Red", "042F56" to "Dark Blue", "A3D8E1" to "Ice Blue",
        "9B9EA0" to "Ash Gray",
        // PLA Basic (additional shades)
        "9CDBD9" to "Arctic Whisper",
        // PETG
        "002E96" to "Blue", "EB3A3A" to "Red", "A3A6A6" to "Gray",
    )

    // Same hex, different name per product line.
    private val matteOverrides = mapOf("000000" to "Charcoal")

    /** [hex] as `RRGGBB` (with or without `#`, any case). Returns null when nothing is close. */
    fun nameFor(hex: String, productName: String? = null): String? {
        val key = hex.removePrefix("#").uppercase().take(6)
        if (key.length != 6) return null
        if (productName?.contains("matte", ignoreCase = true) == true) matteOverrides[key]?.let { return it }
        basic[key]?.let { return it }

        // Nearest match within RGB distance 30, as upstream does.
        val rgb = key.toIntOrNull(16) ?: return null
        var best: String? = null
        var bestDist = 31.0
        for ((h, name) in basic) {
            val other = h.toInt(16)
            val d = distance(rgb, other)
            if (d < bestDist) { bestDist = d; best = name }
        }
        return best
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}
