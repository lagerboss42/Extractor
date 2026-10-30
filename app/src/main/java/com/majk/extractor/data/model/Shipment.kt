package com.majk.extractor.data.model

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
    val latestMinutes: Int? = null
) {
    /**
     * Time-frame column for Excel.
     * - Both minutes == 0 → `08-14`
     * - Otherwise → `08:15-14:30`
     * Empty when either bound is missing.
     */
    fun toTimeFrameColumn(): String {
        val e = earliestMinutes ?: return ""
        val l = latestMinutes ?: return ""
        val eh = e / 60
        val em = e % 60
        val lh = l / 60
        val lm = l % 60
        return if (em == 0 && lm == 0) {
            "%02d-%02d".format(eh, lh)
        } else {
            "%02d:%02d-%02d:%02d".format(eh, em, lh, lm)
        }
    }

    fun toInfoColumn(): String {
        val parts = mutableListOf<String>()

        if (colliCount > 0 || weightKg > 0) {
            parts.add("$colliCount / $weightKg kg")
        }

        if (!portkod.isNullOrBlank()) {
            parts.add(if (portkodRemembered) "{$portkod}" else "PK $portkod")
        }

        if (!comment.isNullOrBlank()) {
            parts.add(comment.trim())
        }

        if (carrier.isNotBlank()) {
            parts.add(carrier)
        }

        return parts.joinToString(" / ")
    }

    fun excelRecipient(): String {
        return if (carrier.equals("Region", ignoreCase = true)) "" else recipient
    }
}
