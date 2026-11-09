package com.majk.extractor.data.model

import com.majk.extractor.util.AddressUtils

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
     * Time-frame column for Excel — always `hh:mm-hh:mm` when both bounds exist.
     * Empty when either bound is missing.
     */
    fun toTimeFrameColumn(): String {
        val e = earliestMinutes ?: return ""
        val l = latestMinutes ?: return ""
        val eh = e / 60
        val em = e % 60
        val lh = l / 60
        val lm = l % 60
        return "%02d:%02d-%02d:%02d".format(eh, em, lh, lm)
    }

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
            parts.add(if (portkodRemembered) "{$portkod}" else "PK $portkod")
        }

        if (!comment.isNullOrBlank() && !isPackageId(comment)) {
            parts.add(comment.trim())
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

    /** Recipient column — blank for Region only when the stop was consolidated. */
    fun excelRecipient(): String {
        val isRegion = carrier.contains("Region", ignoreCase = true)
        return if (isRegion && consolidated) "" else recipient
    }

    /** Combined coords for the trailing Excel column (empty header). */
    fun excelCoordsColumn(): String {
        val la = lat ?: return ""
        val lo = lon ?: return ""
        return AddressUtils.formatCoords(la, lo)
    }
}
