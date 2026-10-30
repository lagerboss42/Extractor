package com.majk.extractor.util

object CarrierNames {

    fun shorten(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.equals("kkböj", ignoreCase = true)) return "kkböj"
        val upper = raw.uppercase()
            .replace("\u00c4", "A")
            .replace("\u00d6", "O")
            .replace("\u00c5", "A")

        return when {
            "TNT" in upper -> "TNT"
            "TWS" in upper -> "TWS"
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
