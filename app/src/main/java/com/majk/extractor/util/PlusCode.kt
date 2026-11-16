package com.majk.extractor.util

/**
 * Open Location Code (Plus Code) encoder.
 * Produces the short form used by the fixed consolidations:
 *   "GFH6+F59 Ljungsbro"
 *
 * 11-character code, first 4 dropped, city appended.
 * Pure local math. No network.
 */
object PlusCode {

    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'

    /** Short code with locality: "XXXX+XXX City". Empty if coords are invalid. */
    fun short(lat: Double, lon: Double, city: String): String {
        val code = encode(lat, lon) ?: return ""
        val short = if (code.length > 4) code.substring(4) else code
        val place = city.trim()
        return if (place.isBlank()) short else "$short $place"
    }

    /** Full 11-character code, or null if out of range. */
    fun encode(lat: Double, lon: Double): String? {
        if (lat.isNaN() || lon.isNaN()) return null
        var latitude = lat.coerceIn(-90.0, 90.0)
        var longitude = lon.coerceIn(-180.0, 180.0)
        if (longitude == 180.0) longitude = -180.0

        latitude += 90.0
        longitude += 180.0

        val sb = StringBuilder(12)
        var latRes = 20.0
        var lonRes = 20.0
        var latRem = latitude
        var lonRem = longitude

        for (i in 0 until 5) {
            val latDigit = (latRem / latRes).toInt().coerceIn(0, 19)
            val lonDigit = (lonRem / lonRes).toInt().coerceIn(0, 19)
            sb.append(ALPHABET[latDigit])
            sb.append(ALPHABET[lonDigit])
            latRem -= latDigit * latRes
            lonRem -= lonDigit * lonRes
            latRes /= 20.0
            lonRes /= 20.0
            if (i == 3) sb.append(SEPARATOR)
        }

        // 11th character: 4×5 refinement grid of the last cell.
        val cellLat = latRes * 20.0
        val cellLon = lonRes * 20.0
        val row = (latRem / (cellLat / 4.0)).toInt().coerceIn(0, 3)
        val col = (lonRem / (cellLon / 5.0)).toInt().coerceIn(0, 4)
        sb.append(ALPHABET[row * 5 + col])

        return sb.toString()
    }
}
