package com.majk.extractor.data.cleaner

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.AddressUtils
import com.majk.extractor.util.CarrierNames
import com.majk.extractor.util.WeightUtils

object ManifestCleaner {

    private val PORT_K_KEYWORDS = listOf(
        "BT", "TMH", "TMHS", "PORT K", "GATE K", "TOYOTA", "MATERIAL HANDLING", "HALLEVAD"
    )

    private val PORT_K_EXCLUDE = listOf("PORT F", "GATE F", "TRUCKHUSET")

    private val PORT_F_KEYWORDS = listOf("PORT F", "GATE F", "TRUCKHUSET")

    private val PORT_D_KEYWORDS = listOf("PORT D", "GATE D")

    private val CLOETTA_KEYWORDS = listOf("CLOETTA")

    private const val PORT_K_RECIPIENT = "Port K"
    private const val PORT_K_ZIP = "59535"
    private const val PORT_K_CITY = "Mjölby"
    private const val PORT_K_ADDRESS = "8493+J2P Mjölby"

    private const val PORT_F_RECIPIENT = "Port F"
    private const val PORT_F_ZIP = "59535"
    private const val PORT_F_CITY = "Mjölby"
    private const val PORT_F_ADDRESS = "84C3+H7H Mjölby"

    private const val PORT_D_RECIPIENT = "Port D"
    private const val PORT_D_ZIP = "59535"
    private const val PORT_D_CITY = "Mjölby"
    private const val PORT_D_ADDRESS = "8496+2RC Mjölby"

    /** Delivery / godsmottagning site. */
    private const val CLOETTA_DEL_RECIPIENT = "Cloetta Godsmottagning"
    private const val CLOETTA_DEL_ADDRESS = "GG72+6F Ljungsbro"
    /** Pickup site (not the same as godsmottagning). */
    private const val CLOETTA_PICK_RECIPIENT = "Cloetta"
    private const val CLOETTA_PICK_ADDRESS = "GF7X+3V Ljungsbro"
    private const val CLOETTA_ZIP = "58580"
    private const val CLOETTA_CITY = "Ljungsbro"

    fun cleanAndConsolidate(raw: List<Shipment>): List<Shipment> {
        val cleaned = raw.map { cleanSingle(it) }
        return consolidate(cleaned)
    }

    private fun cleanSingle(s: Shipment): Shipment {
        val spaced = AddressUtils.fixSpacing(s.address)
        val comment = s.comment?.trim()?.takeIf { it.isNotEmpty() }
        val found = s.lat?.let { la -> s.lon?.let { lo -> AddressUtils.Coords(la, lo) } }
            ?: AddressUtils.extractCoords(spaced, comment, s.recipient)
        // Prefer capture/hard-coded coords over street in Address. Never Nominatim here.
        val address = if (found != null) found.format() else spaced
        return s.copy(
            recipient = companyOnly(s.recipient),
            sender = s.sender?.let { companyOnly(it) },
            address = address,
            city = AddressUtils.cleanCity(s.city),
            weightKg = WeightUtils.roundToWhole(s.weightKg),
            carrier = CarrierNames.shorten(s.carrier),
            portkod = s.portkod?.trim()?.takeIf { it.isNotEmpty() },
            comment = comment,
            lat = found?.lat ?: s.lat,
            lon = found?.lon ?: s.lon
        )
    }

    /** Company / site name only; strip slash-suffix and drop duplicate name halves. */
    private fun companyOnly(name: String): String {
        var trimmed = name.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return trimmed
        if ("/" in trimmed) {
            val parts = trimmed.split("/").map { it.trim() }.filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            for (p in parts) {
                if (out.isEmpty()) {
                    out.add(p)
                } else {
                    val prev = out.last()
                    if (prev.equals(p, ignoreCase = true)) continue
                    if (p.contains(prev, ignoreCase = true) || prev.contains(p, ignoreCase = true)) {
                        if (p.length > prev.length) out[out.lastIndex] = p
                        continue
                    }
                    out.add(p)
                }
            }
            trimmed = out.joinToString(" / ")
        }
        val beforeSlash = trimmed.substringBefore("/").trim()
        return beforeSlash.ifEmpty { trimmed }
    }

    private fun consolidate(list: List<Shipment>): List<Shipment> {
        val portK = mutableListOf<Shipment>()
        val portF = mutableListOf<Shipment>()
        val portD = mutableListOf<Shipment>()
        val cloetta = mutableListOf<Shipment>()
        val rest = mutableListOf<Shipment>()

        for (s in list) {
            when {
                isPortF(s) -> portF.add(s)
                isPortD(s) -> portD.add(s)
                isPortK(s) -> portK.add(s)
                isCloetta(s) -> cloetta.add(s)
                else -> rest.add(s)
            }
        }

        val result = mutableListOf<Shipment>()
        // Same-type only: never merge pickups with deliveries at a fixed site.
        addFixedByType(result, portK, PORT_K_RECIPIENT, PORT_K_ADDRESS, PORT_K_ZIP, PORT_K_CITY)
        addFixedByType(result, portF, PORT_F_RECIPIENT, PORT_F_ADDRESS, PORT_F_ZIP, PORT_F_CITY)
        addFixedByType(result, portD, PORT_D_RECIPIENT, PORT_D_ADDRESS, PORT_D_ZIP, PORT_D_CITY)
        // Cloetta pickup vs godsmottagning are different places + different recipient labels.
        val cloettaPick = cloetta.filter { it.type == ShipmentType.PICK }
        val cloettaDel = cloetta.filter { it.type != ShipmentType.PICK }
        if (cloettaPick.isNotEmpty()) {
            result.add(mergeFixedPort(cloettaPick, CLOETTA_PICK_RECIPIENT, CLOETTA_PICK_ADDRESS, CLOETTA_ZIP, CLOETTA_CITY))
        }
        if (cloettaDel.isNotEmpty()) {
            result.add(mergeFixedPort(cloettaDel, CLOETTA_DEL_RECIPIENT, CLOETTA_DEL_ADDRESS, CLOETTA_ZIP, CLOETTA_CITY))
        }

        val grouped = rest.groupBy {
            Triple(
                it.type,
                normalizeRecipientKey(it.recipient),
                "${it.address.lowercase()}|${it.zip}"
            )
        }
        for ((_, group) in grouped) {
            result.add(if (group.size == 1) group.first() else mergeGroup(group))
        }

        return result.sortedWith(
            compareBy<Shipment> { it.type }
                .thenBy { it.city }
                .thenBy { it.address }
        )
    }

    private fun haystack(s: Shipment): String =
        normalizeAscii(listOf(s.recipient, s.address, s.sender.orEmpty()).joinToString(" "))

    private fun isMjolby(s: Shipment): Boolean =
        "MJOLBY" in normalizeAscii(s.city)

    private fun isTruckgatan2(s: Shipment): Boolean {
        val addr = normalizeAscii(s.address).replace(" ", "")
        val zip = s.zip.filter { it.isDigit() }
        return "TRUCKGATAN2" in addr && (zip.isEmpty() || zip == "59535")
    }

    private fun isPortF(s: Shipment): Boolean {
        if (isTruckgatan2(s)) return true
        val h = haystack(s)
        return PORT_F_KEYWORDS.any { normalizeAscii(it) in h }
    }

    private fun isPortD(s: Shipment): Boolean {
        val h = haystack(s)
        return PORT_D_KEYWORDS.any { normalizeAscii(it) in h }
    }

    private fun isPortK(s: Shipment): Boolean {
        if (!isMjolby(s)) return false
        if (isPortF(s) || isPortD(s)) return false
        val h = haystack(s)
        if (PORT_K_EXCLUDE.any { normalizeAscii(it) in h }) return false
        if (isTruckgatan2(s)) return false
        return PORT_K_KEYWORDS.any { normalizeAscii(it) in h }
    }

    private fun isCloetta(s: Shipment): Boolean {
        val h = haystack(s)
        return CLOETTA_KEYWORDS.any { normalizeAscii(it) in h }
    }

    private fun normalizeAscii(text: String): String =
        text.uppercase()
            .replace("\u00d6", "O")
            .replace("\u00c4", "A")
            .replace("\u00c5", "A")

    private fun mergeCarriers(group: List<Shipment>): String {
        return group.map { it.carrier }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .ifBlank { "" }
    }

    private fun addFixedByType(
        result: MutableList<Shipment>,
        group: List<Shipment>,
        recipient: String,
        address: String,
        zip: String,
        city: String
    ) {
        if (group.isEmpty()) return
        val picks = group.filter { it.type == ShipmentType.PICK }
        val dels = group.filter { it.type != ShipmentType.PICK }
        if (picks.isNotEmpty()) result.add(mergeFixedPort(picks, recipient, address, zip, city))
        if (dels.isNotEmpty()) result.add(mergeFixedPort(dels, recipient, address, zip, city))
    }

    /**
     * Fixed-site merge. [address] is a plus code + city (e.g. "8493+J2P Mjölby").
     * Caller must pass a same-type group only (pickups or deliveries, never mixed).
     */
    private fun mergeFixedPort(
        group: List<Shipment>,
        recipient: String,
        address: String,
        zip: String,
        city: String
    ): Shipment {
        val type = group.first().type
        val comments = group.mapNotNull { it.comment }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .ifBlank { null }

        return Shipment(
            type = type,
            recipient = recipient,
            address = address,
            zip = zip,
            city = city,
            colliCount = group.sumOf { it.colliCount },
            weightKg = group.sumOf { it.weightKg },
            portkod = group.mapNotNull { it.portkod }.firstOrNull(),
            comment = comments,
            carrier = mergeCarriers(group).ifBlank { "TNT" },
            lat = null,
            lon = null,
            earliestMinutes = group.mapNotNull { it.earliestMinutes }.minOrNull(),
            latestMinutes = group.mapNotNull { it.latestMinutes }.maxOrNull()
        )
    }

    private fun mergeGroup(group: List<Shipment>): Shipment {
        val first = group.first()
        val totalColli = group.sumOf { it.colliCount }
        val totalWeight = group.sumOf { it.weightKg }

        val recipients = group.map { it.recipient }.filter { it.isNotBlank() }.distinct()
        val mergedRecipient = when {
            recipients.isEmpty() -> first.recipient
            recipients.size == 1 -> recipients.first()
            else -> recipients.joinToString(", ")
        }

        val bestPortkod = group.mapNotNull { it.portkod }.firstOrNull()
        val bestComment = group.mapNotNull { it.comment }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .ifBlank { null }

        val carriers = mergeCarriers(group)

        val coords = group.mapNotNull { s ->
            when {
                s.lat != null && s.lon != null -> AddressUtils.Coords(s.lat, s.lon)
                else -> AddressUtils.extractCoords(s.address, s.comment, s.recipient)
            }
        }.firstOrNull()

        val lat = coords?.lat ?: first.lat
        val lon = coords?.lon ?: first.lon
        // Only put capture coords in Address — never Nominatim-only.
        val address = if (lat != null && lon != null) {
            AddressUtils.formatCoords(lat, lon)
        } else {
            first.address
        }
        return first.copy(
            recipient = mergedRecipient,
            address = address,
            colliCount = totalColli,
            weightKg = totalWeight,
            portkod = bestPortkod,
            comment = bestComment,
            carrier = carriers.ifBlank { first.carrier },
            lat = lat,
            lon = lon,
            earliestMinutes = group.mapNotNull { it.earliestMinutes }.minOrNull()
                ?: first.earliestMinutes,
            latestMinutes = group.mapNotNull { it.latestMinutes }.maxOrNull()
                ?: first.latestMinutes
        )
    }

    private fun normalizeRecipientKey(recipient: String): String {
        return recipient.substringBefore("/").trim().uppercase()
    }
}
