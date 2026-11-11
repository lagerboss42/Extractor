package com.majk.extractor.util

/**
 * Repair Swedish UTF-8 mojibake (single and double) so display text has real ÅÄÖåäö.
 * Wire traffic is often UTF-8 interpreted as Latin-1 once or twice.
 */
object TextEncoding {

    fun fixSwedish(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        repeat(3) {
            val prev = s
            s = s
                .replace("\u00C3\u0083\u00C2\u00A5", "\u00E5")
                .replace("\u00C3\u0083\u00C2\u00A4", "\u00E4")
                .replace("\u00C3\u0083\u00C2\u00B6", "\u00F6")
                .replace("\u00C3\u0083\u00C2\u0085", "\u00C5")
                .replace("\u00C3\u0083\u00C2\u0084", "\u00C4")
                .replace("\u00C3\u0083\u00C2\u0096", "\u00D6")
                .replace("\u00C3\u0083\u00A5", "\u00E5")
                .replace("\u00C3\u0083\u00A4", "\u00E4")
                .replace("\u00C3\u0083\u00B6", "\u00F6")
                .replace("\u00C3\u0083\u0085", "\u00C5")
                .replace("\u00C3\u0083\u0084", "\u00C4")
                .replace("\u00C3\u0083\u0096", "\u00D6")
                .replace("\u00C3\u00A5", "\u00E5")
                .replace("\u00C3\u00A4", "\u00E4")
                .replace("\u00C3\u00B6", "\u00F6")
                .replace("\u00C3\u0085", "\u00C5")
                .replace("\u00C3\u0084", "\u00C4")
                .replace("\u00C3\u0096", "\u00D6")
                .replace("Ã¥", "\u00E5").replace("Ã¤", "\u00E4").replace("Ã¶", "\u00F6")
                .replace("Ã…", "\u00C5").replace("Ã„", "\u00C4").replace("Ã–", "\u00D6")
            if (s == prev) return@repeat
        }
        return s
    }

    fun foldAscii(text: String): String {
        var s = fixSwedish(text).lowercase()
        s = s.replace("\u00E5", "a").replace("\u00E4", "a").replace("\u00F6", "o")
            .replace("\u00E9", "e").replace("\u00FC", "u")
        s = s
            .replace("\u00C3\u00A5", "a").replace("\u00C3\u00A4", "a").replace("\u00C3\u00B6", "o")
            .replace("\u00C3\u0085", "a").replace("\u00C3\u0084", "a").replace("\u00C3\u0096", "o")
            .replace("\u00A5", "a").replace("\u00A4", "a").replace("\u00B6", "o")
        return s
    }

    /**
     * Restore Swedish letters in addresses and city names.
     * Digraphs: oe→ö, ae→ä, ao→å.
     * A lone ? is å, ä or ö only when the whole token matches a known city
     * with every other character identical.
     */
    fun restoreSwedish(text: String): String {
        if (text.isEmpty()) return text
        var s = fixSwedish(text)
        s = s.replace("oe", "ö").replace("Oe", "Ö").replace("OE", "Ö")
            .replace("ae", "ä").replace("Ae", "Ä").replace("AE", "Ä")
            .replace("ao", "å").replace("Ao", "Å").replace("AO", "Å")
        return s.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { token ->
            if (token.isBlank()) token else matchCity(token) ?: token
        }
    }

    private val CITIES = listOf(
        "Linköping", "Malmslätt", "Ljungsbro", "Vreta", "Kloster",
        "Mantorp", "Skänninge", "Mjölby", "Vadstena", "Motala",
        "Borensberg", "Tjällmo", "Ödeshög", "Väderstad", "Åtvidaberg"
    )

    private fun matchCity(token: String): String? {
        for (city in CITIES) {
            if (token.length != city.length) continue
            if (!token.indices.all { i -> sameCityChar(token[i], city[i]) }) continue
            return when {
                token.all { !it.isLetter() || it.isUpperCase() } && token.any { it.isLetter() } ->
                    city.uppercase()
                token.first().isUpperCase() -> city
                else -> city.lowercase()
            }
        }
        return null
    }

    /** Exact, or å/ä/ö written as a/o, or as ? when the rest of the city matches. */
    private fun sameCityChar(got: Char, want: Char): Boolean {
        if (got.equals(want, ignoreCase = true)) return true
        if (got == '?' && want in "åäöÅÄÖ") return true
        if (got.equals('a', true) && want in "åäÅÄ") return true
        if (got.equals('o', true) && want in "öÖ") return true
        return false
    }
}
