package com.majk.extractor.util

/**
 * Open Location Code (Plus Code) encoder and decoder.
 * Produces the short form used by the fixed consolidations:
 *   "GFH6+F59 Ljungsbro"
 *
 * 11-character code, first 4 dropped, city appended.
 * Pure local math. No network, no library.
 */
object PlusCode {

    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'

    /** Known city centroids for short-code recovery. Service area around Linköping. */
    private val CITY_CENTROIDS = mapOf(
        "linköping" to (58.4109 to 15.6216),
        "linkoping" to (58.4109 to 15.6216),
        "mjölby" to (58.3260 to 15.1237),
        "mjolby" to (58.3260 to 15.1237),
        "skänninge" to (58.3943 to 15.0866),
        "skanninge" to (58.3943 to 15.0866),
        "ljungsbro" to (58.5066 to 15.5084),
        "motala" to (58.5371 to 15.0365),
        "mantorp" to (58.3510 to 15.2851),
        "vadstena" to (58.4486 to 14.8897)
    )

    private const val FALLBACK_LAT = 58.4
    private const val FALLBACK_LON = 15.6

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

    /**
     * Decode a full or short Plus Code to the cell centre.
     * Full 11-char (XXXXXXXX+XXX) decodes directly.
     * Short (XXXX+XXX or XXXX+XXX City) recovers the dropped 4 chars from a reference:
     * current lat/lon if present, else a known-city centroid, else the Linköping service-area fallback.
     * Returns null on bad character, missing +, or out-of-range result.
     */
    fun decode(
        code: String,
        refLat: Double? = null,
        refLon: Double? = null,
        city: String? = null
    ): Pair<Double, Double>? {
        val cleaned = code.trim()
        if (cleaned.isEmpty() || '+' !in cleaned) return null

        val (codePart, cityPart) = splitCodeAndCity(cleaned)
        if (codePart.isEmpty()) return null

        val full = if (isFullCode(codePart)) {
            codePart
        } else {
            recoverFull(codePart, refLat, refLon, cityPart ?: city)
        } ?: return null

        return decodeFull(full)
    }

    private fun splitCodeAndCity(raw: String): Pair<String, String?> {
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val code = parts[0].uppercase()
        val city = if (parts.size > 1) parts[1].trim() else null
        return code to city
    }

    private fun isFullCode(code: String): Boolean {
        // encode() writes 8 chars, '+', then 3 chars: "XXXXXXXX+XXX" (12).
        if (code.length != 12 || code[8] != SEPARATOR) return false
        return code.all { it == SEPARATOR || ALPHABET.indexOf(it) >= 0 }
    }

    private fun recoverFull(
        short: String,
        refLat: Double?,
        refLon: Double?,
        city: String?
    ): String? {
        val ref = referencePoint(refLat, refLon, city) ?: return null
        val area = encode(ref.first, ref.second) ?: return null
        if (area.length < 4) return null
        val prefix = area.substring(0, 4)
        val candidate = prefix + short
        return if (isFullCode(candidate)) candidate else null
    }

    private fun referencePoint(refLat: Double?, refLon: Double?, city: String?): Pair<Double, Double>? {
        if (refLat != null && refLon != null && !refLat.isNaN() && !refLon.isNaN()) {
            return refLat to refLon
        }
        val key = city?.trim()?.lowercase()
            ?.replace("ö", "o")?.replace("ä", "a")?.replace("å", "a")
            ?.replace("oe", "o")
        CITY_CENTROIDS[key]?.let { return it }
        // Service-area fallback around Linköping (lat 56.2–59.9, lon 11.1–19.3).
        return FALLBACK_LAT to FALLBACK_LON
    }

    /** Decode a full code to the centre of its cell. Reverses [encode]. */
    private fun decodeFull(code: String): Pair<Double, Double>? {
        if (!isFullCode(code)) return null
        val digits = code.filter { it != SEPARATOR }
        if (digits.length != 11) return null

        var lat = 0.0
        var lon = 0.0
        var latRes = 20.0
        var lonRes = 20.0

        for (i in 0 until 5) {
            val latDigit = ALPHABET.indexOf(digits[i * 2])
            val lonDigit = ALPHABET.indexOf(digits[i * 2 + 1])
            if (latDigit < 0 || lonDigit < 0) return null
            lat += latDigit * latRes
            lon += lonDigit * lonRes
            latRes /= 20.0
            lonRes /= 20.0
        }

        // 11th character is the 4×5 refinement grid of the last cell.
        val gridIndex = ALPHABET.indexOf(digits[10])
        if (gridIndex < 0) return null
        val row = gridIndex / 5
        val col = gridIndex % 5
        val cellLat = latRes * 20.0
        val cellLon = lonRes * 20.0
        val fineLat = cellLat / 4.0
        val fineLon = cellLon / 5.0
        lat += row * fineLat + fineLat / 2.0
        lon += col * fineLon + fineLon / 2.0

        val latitude = lat - 90.0
        val longitude = lon - 180.0
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }
}
