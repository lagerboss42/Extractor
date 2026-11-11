package com.majk.extractor.util

object AddressUtils {
    fun fixSpacing(address: String): String {
        val restored = TextEncoding.restoreSwedish(address)
        return restored.replace(Regex("([A-Za-zÅÄÖåäö])(\\d+)$")) {
            "${it.groupValues[1]} ${it.groupValues[2]}"
        }.trim()
    }

    fun cleanCity(city: String): String {
        return TextEncoding.restoreSwedish(city)
            .replace(Regex("\\s+\\d+$"), "")
            .trim()
    }

    /**
     * Area of interest (Östergötland / SE ops): lat 56.2–59.9, lon 11.1–19.3.
     * Accepts 4–8 decimal digits; comma or dot decimals; separators comma / space / semicolon.
     *
     * Examples:
     *   58.123456, 15.123456
     *   58,123456, 15,123456
     *   58,123456 15,123456
     */
    private val COORDS = Regex(
        """(?<![\d.,])([5][6-9][.,]\d{4,8}|[5][6-9]\.\d{4,8})\s*[,;\s]\s*([1][1-9][.,]\d{4,8}|[1][1-9]\.\d{4,8})(?![\d.,])"""
    )

    data class Coords(val lat: Double, val lon: Double) {
        /** Preferred export form: 58.123456, 15.123456 */
        fun format(): String = formatCoords(lat, lon)
    }

    fun formatCoords(lat: Double, lon: Double): String {
        fun fmt(v: Double): String {
            val s = "%.8f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
            // keep at least 4 decimals when possible
            val dot = s.indexOf('.')
            return if (dot < 0) s else {
                val decimals = s.length - dot - 1
                if (decimals < 4) "%.4f".format(java.util.Locale.US, v) else s
            }
        }
        return "${fmt(lat)}, ${fmt(lon)}"
    }

    private fun parseCoordToken(raw: String): Double? {
        val t = raw.trim().replace(',', '.')
        return t.toDoubleOrNull()
    }

    fun extractCoords(vararg parts: String?): Coords? {
        val text = parts.filterNotNull().joinToString(" ")
        if (text.isBlank()) return null
        // Normalize unicode spaces
        val normalized = text.replace('\u00A0', ' ')
        val m = COORDS.find(normalized) ?: return null
        val lat = parseCoordToken(m.groupValues[1]) ?: return null
        val lon = parseCoordToken(m.groupValues[2]) ?: return null
        if (lat !in 56.2..59.9 || lon !in 11.1..19.3) return null
        return Coords(lat, lon)
    }
}
