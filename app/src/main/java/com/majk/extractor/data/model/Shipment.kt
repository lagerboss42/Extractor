package com.majk.extractor.data.model

import com.majk.extractor.util.AddressUtils
import com.majk.extractor.util.CarrierNames

enum class ShipmentType {
    DEL,
    PICK;

    fun displayName(): String = when (this) {
        DEL -> "delivery"
        PICK -> "pickup"
    }
}

data class Shipment(
    val type: ShipmentType,
    val recipient: String,
    val address: String,
    val zip: String,
    val city: String,
    val colliCount: Int,
    val weightKg: Int,
    val portkod: String?,
    /** Free-text notes from shipment detail (e.g. "Fullmakt ytterdörr", "Ring 30 min") */
    val comment: String?,
    val carrier: String,
    val rawId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val sender: String? = null,
    /** True when portkod was filled from the saved address database, not this shipment. */
    val portkodRemembered: Boolean = false,
    /**
     * Earliest delivery time-of-day as minutes from midnight
     * (from Opter EARLIESTDELIVERYTIME / DEL timestamps). Null if unknown.
     */
    val earliestMinutes: Int? = null,
    /**
     * Latest delivery time-of-day as minutes from midnight
     * (from Opter LATESTDELIVERYTIME / DEL timestamps). Null if unknown.
     */
    val latestMinutes: Int? = null,
    /** True when this Region stop was merged from multiple packages of the same recipient. */
    val consolidated: Boolean = false
) {
    /**
     * Time-frame column. `hh:mm-hh:mm` when both bounds exist.
     * Latest only when the earliest is unknown. Empty when neither exists.
     */
    fun toTimeFrameColumn(): String {
        val (e, l) = resolveWindow()
        if (l == null) return ""
        val lh = l / 60
        val lm = l % 60
        if (e == null) return "%02d:%02d".format(lh, lm)
        val eh = e / 60
        val em = e % 60
        return "%02d:%02d-%02d:%02d".format(eh, em, lh, lm)
    }

    /** No invented windows. Only the bounds the capture actually carried. */
    private fun resolveWindow(): Pair<Int?, Int?> = earliestMinutes to latestMinutes

    /**
     * Info column layout (fixed order):
     *   colli / weight / pk / other / carrier
     *
     * Example:  "4 / 56 kg / PK 1234 / Fullmakt ytterdörr / TNT"
     */
    fun toInfoColumn(): String {
        val parts = mutableListOf<String>()

        if (colliCount > 0 || weightKg > 0) {
            parts.add("$colliCount / $weightKg kg")
        }

        if (!portkod.isNullOrBlank()) {
            val codes = portkod.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(4)
            if (codes.isNotEmpty()) {
                parts.add(
                    if (portkodRemembered) "pk ${codes.joinToString(", ")}"
                    else "PK ${codes.joinToString(", ")}"
                )
            }
        }

        if (!comment.isNullOrBlank()) {
            val kept = stripJunk(comment)
            if (kept.isNotBlank() && isAllowedNote(kept)) parts.add(kept)
        }

        if (carrier.isNotBlank()) {
            parts.add(carrier)
        }

        return parts.joinToString(" / ")
    }

    private fun isPackageId(v: String): Boolean {
        val digits = v.count { it.isDigit() }
        return digits >= 10 && digits.toDouble() / v.length > 0.8
    }

    private val statusWords = setOf(
        "skapad", "meddelad", "nekad", "lastad", "lossad", "makulerad",
        "bekraftad", "avbruten", "ombokad", "utlamnad",
        "testprojekt", "test"
    )

    /** Drop package IDs and Opter status words from the free-text part of Info. */
    private fun stripJunk(raw: String): String {
        return raw.split(Regex("\\s*/\\s*|\\s*\\|\\s*"))
            .map { it.trim() }
            .filter { token ->
                token.isNotBlank() &&
                    !isPackageId(token) &&
                    fold(token) !in statusWords &&
                    !isCarrierToken(token)
            }
            .joinToString(" / ")
    }

    private fun isAllowedNote(v: String): Boolean {
        val folded = fold(v)
        if (folded.startsWith("test")) return false
        return folded.startsWith("lgh") ||
            folded.startsWith("ring") ||
            folded.startsWith("okej") ||
            "fullmakt" in folded ||
            "portkod" in folded ||
            "lamna" in folded ||
            "paket" in folded ||
            "dorr" in folded ||
            "utanfor" in folded ||
            Regex("""\bkod\b""").containsMatchIn(folded)
    }

    private fun isCarrierToken(token: String): Boolean {
        if (token.equals(carrier, ignoreCase = true)) return true
        val short = CarrierNames.shorten(token)
        return short.isNotBlank() && short != token.trim() && short.equals(carrier, ignoreCase = true)
    }

    private fun fold(s: String): String =
        s.lowercase().replace("ä", "a").replace("ö", "o").replace("å", "a").replace("é", "e")

    /** Recipient column — blank for Region unless the same address has different recipients. */
    fun excelRecipient(): String {
        val isRegion = carrier.contains("Region", ignoreCase = true)
        return if (isRegion && !consolidated) "" else recipient
    }

    /** Combined coords for the trailing Excel column (empty header). */
    fun excelCoordsColumn(): String {
        val la = lat ?: return ""
        val lo = lon ?: return ""
        return AddressUtils.formatCoords(la, lo)
    }
}
