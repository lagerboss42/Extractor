package com.majk.extractor.util

object AddressUtils {
    fun fixSpacing(address: String): String {
        return address.replace(Regex("([A-Za-z\u00c5\u00c4\u00d6\u00e5\u00e4\u00f6])(\\d+)$")) {
            "${it.groupValues[1]} ${it.groupValues[2]}"
        }.trim()
    }

    fun cleanCity(city: String): String {
        return city.replace(Regex("\\s+\\d+$"), "").trim()
    }

    /**
     * Swedish-ish WGS84 pair: 55–70 lat, 10–25 lon.
     * Accepts "58.319078, 15.102488" or "58.319078 15.102488".
     */
    private val COORDS = Regex(
        """(?<![\\d.])([5-6]\\d\\.\\d{3,})\\s*[,;\\s]\\s*([1][0-9]\\.\\d{3,}|[2][0-5]\\.\\d{3,})(?![\\d.])"""
    )

    data class Coords(val lat: Double, val lon: Double)

    fun extractCoords(vararg parts: String?): Coords? {
        val text = parts.filterNotNull().joinToString(" ")
        if (text.isBlank()) return null
        val m = COORDS.find(text) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in 55.0..70.0 || lon !in 10.0..25.0) return null
        return Coords(lat, lon)
    }

    /** True if the whole address field is essentially only coordinates. */
    fun isCoordsOnlyAddress(address: String): Boolean {
        val t = address.trim()
        if (t.isEmpty()) return false
        val c = extractCoords(t) ?: return false
        val stripped = t
            .replace(c.lat.toString(), "")
            .replace("%.6f".format(c.lat), "")
            .replace(Regex("""[5-6]\\d\\.\\d+"""), "")
            .replace(Regex("""[12]\\d\\.\\d+"""), "")
            .replace(Regex("""[\\s,;]+"""), "")
        return stripped.isEmpty()
    }
}
