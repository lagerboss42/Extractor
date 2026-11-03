package com.majk.extractor.util

/**
 * Standardise carrier / delivery-type names for Excel output.
 *
 * Rules:
 *   TNT*                      → TNT
 *   Region*                   → Region
 *   Närkefrakt* / Narke*      → Närke
 *   Freja*                    → Freja
 *   Dachser*                  → Dachser
 *   Solar* + F7               → Solar F7
 *   Solar* + F12              → Solar F12
 *   Apoteket Nationella*      → APONAT
 *   TWS*                      → TWS
 *   Onninen* / Kesko*         → Kesko
 */
object CarrierNames {

    fun shorten(raw: String): String {
        if (raw.isBlank()) return ""
        // Preserve fixed-stop marker
        if (raw.equals("kkböj", ignoreCase = true)) return "kkböj"
        val upper = raw.uppercase()
            .replace("Ä", "A")
            .replace("Ö", "O")
            .replace("Å", "A")

        return when {
            "TNT" in upper -> "TNT"
            "TWS" in upper -> "TWS"
            "ONNINEN" in upper || "KESKO" in upper -> "Kesko"
            "DACHSER" in upper -> "Dachser"
            "REGION" in upper -> "Region"
            "NARKEFRAKT" in upper || "NARKE FRAKT" in upper ||
                (upper.startsWith("NARKE") && "FRAKT" in upper) -> "Närke"
            "FREJA" in upper -> "Freja"
            "SOLAR" in upper -> {
                when {
                    "F12" in upper || "F-12" in upper -> "Solar F12"
                    "F7" in upper || "F-7" in upper -> "Solar F7"
                    else -> "Solar"
                }
            }
            "APONAT" in upper -> "APONAT"
            "APOTEKET NATIONELLA" in upper || "APOTEKET NATIONELL" in upper -> "APONAT"
            "PACKOPLOCK" in upper -> "Packoplock"
            "BEST TRANSPORT" in upper -> "Best Transport"
            "ALLOFFICE" in upper -> "Alloffice"
            else -> raw.trim()
        }
    }
}
