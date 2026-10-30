package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.CarrierNames
import java.util.regex.Pattern

/**
 * Parses Opter Driver network traffic into Shipment objects.
 *
 * Supports:
 *  1. Tab-separated DEL records (load / package scan) — preferred, full field map
 *  2. Embedded address strings from list-load
 *  3. Binary TLV list-load (OpterBinaryParser)
 */
object OpterHttpParser {

    private val TERMINAL_STREETS = listOf("köpetorpsgatan", "kromgatan")
    private val TERMINAL_NAMES = listOf("alltransport")

    fun parse(payload: String): List<Shipment> {
        val results = mutableListOf<Shipment>()
        results += parseDelRecords(payload)
        results += parseEmbeddedAddresses(payload)
        results += OpterBinaryParser.parse(payload)
        return results
    }

    private fun parseDelRecords(payload: String): List<Shipment> {
        val shipments = mutableListOf<Shipment>()
        val delPattern = Pattern.compile("""DEL\t([^\r\n\x00]{50,2000})""", Pattern.MULTILINE)
        val matcher = delPattern.matcher(payload)

        while (matcher.find()) {
            val line = "DEL\t" + matcher.group(1)
            val fields = line.split("\t")
            if (fields.size < 46) continue

            try {
                val shipmentId = fields.getOrNull(8)?.trim().orEmpty()
                val recipientName = fields.getOrNull(10)?.trim().orEmpty()
                val recipientCity = fields.getOrNull(11)?.trim().orEmpty()
                val senderName = fields.getOrNull(12)?.trim().orEmpty()
                val senderCity = fields.getOrNull(13)?.trim().orEmpty()

                val senderStreet = fields.getOrNull(21)?.trim().orEmpty()
                val senderNumber = fields.getOrNull(22)?.trim().orEmpty()
                val senderZip = fields.getOrNull(23)?.trim()?.filter { it.isDigit() }?.take(5).orEmpty()

                val recipientStreet = fields.getOrNull(26)?.trim().orEmpty()
                val recipientNumber = fields.getOrNull(27)?.trim().orEmpty()
                val recipientZip = fields.getOrNull(28)?.trim()?.filter { it.isDigit() }?.take(5).orEmpty()

                val carrierRaw = fields.getOrNull(33)?.trim().orEmpty()
                val weightRaw = fields.getOrNull(40)?.trim().orEmpty()
                    .ifBlank { fields.getOrNull(39)?.trim().orEmpty() }
                val colliRaw = fields.getOrNull(45)?.trim().orEmpty()

                val senderIsTerminal = isTerminalAddress(senderStreet, senderName)
                val recipientIsTerminal = isTerminalAddress(recipientStreet, recipientName)

                val resolved = when {
                    senderIsTerminal && !recipientIsTerminal ->
                        Tuple7(ShipmentType.DEL, recipientName, recipientStreet, recipientNumber, recipientZip, recipientCity, senderName)
                    recipientIsTerminal && !senderIsTerminal ->
                        Tuple7(ShipmentType.PICK, senderName, senderStreet, senderNumber, senderZip, senderCity, recipientName)
                    !recipientIsTerminal && recipientStreet.isNotBlank() ->
                        Tuple7(ShipmentType.DEL, recipientName, recipientStreet, recipientNumber, recipientZip, recipientCity, senderName)
                    !senderIsTerminal && senderStreet.isNotBlank() ->
                        Tuple7(ShipmentType.PICK, senderName, senderStreet, senderNumber, senderZip, senderCity, recipientName)
                    else -> null
                } ?: continue

                if (resolved.name.isBlank() && resolved.street.isBlank()) continue
                if (resolved.zip.length != 5 && resolved.street.isBlank()) continue

                val (recStreet, recNumber) = recoverMissingStreet(resolved.name, resolved.street, resolved.number)
                val address = buildAddress(recStreet, recNumber)
                if (address.isBlank()) continue

                val colli = colliRaw.toIntOrNull()?.coerceAtLeast(0) ?: 1
                val weight = parseWeight(weightRaw)
                val carrier = shortenCarrier(carrierRaw)
                val (earliestMin, latestMin) = parseDelTimeWindow(fields.getOrNull(49), fields.getOrNull(58))

                shipments.add(
                    Shipment(
                        type = resolved.type,
                        recipient = resolved.name,
                        address = address,
                        zip = resolved.zip,
                        city = resolved.city,
                        colliCount = if (colli > 0) colli else 1,
                        weightKg = weight,
                        portkod = null,
                        comment = null,
                        carrier = carrier,
                        sender = resolved.other.ifBlank { null },
                        rawId = shipmentId.ifBlank { null },
                        earliestMinutes = earliestMin,
                        latestMinutes = latestMin
                    )
                )
            } catch (_: Exception) {
            }
        }
        return consolidateByRawId(shipments)
    }

    private data class Tuple7(
        val type: ShipmentType,
        val name: String,
        val street: String,
        val number: String,
        val zip: String,
        val city: String,
        val other: String
    )

    private fun parseEmbeddedAddresses(payload: String): List<Shipment> {
        val shipments = mutableListOf<Shipment>()
        val normalized = payload.replace(Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]+"), "\t")
        val pattern = Pattern.compile(
            """([A-Za-zÅÄÖåäö0-9 /&\\-.,]{4,80})\\t+([A-Za-zÅÄÖåäö0-9 ]{2,40})\\t+(\\d{0,4}[A-Za-z]?)\\t+(\\d{5})\\t+([A-Za-zÅÄÖåäö]{2,25})"""
        )
        val matcher = pattern.matcher(normalized)
        val seen = mutableSetOf<String>()

        while (matcher.find()) {
            val name = matcher.group(1).trim()
            val street = matcher.group(2).trim()
            val number = matcher.group(3).trim()
            val zip = matcher.group(4).trim()
            val city = matcher.group(5).trim()

            if (name.length < 4) continue
            if (street.lowercase() in listOf("sweden", "false", "true", "standard")) continue
            if (!zip.matches(Regex("\\d{5}"))) continue

            val key = "$name|$street|$zip"
            if (key in seen) continue
            seen.add(key)

            val address = buildAddress(street, number)
            if (isTerminalAddress(street, name)) continue

            shipments.add(
                Shipment(
                    type = ShipmentType.DEL,
                    recipient = name,
                    address = address,
                    zip = zip,
                    city = city,
                    colliCount = 1,
                    weightKg = 0,
                    portkod = null,
                    comment = null,
                    carrier = "",
                    rawId = null
                )
            )
        }
        return shipments
    }

    fun classifyByStatus(shipment: Shipment, status: String?): Shipment {
        if (status.isNullOrBlank()) return shipment
        val s = status.lowercase()
        return when {
            "bekräftad" in s || "bekraftad" in s -> shipment.copy(type = ShipmentType.PICK)
            "lastad" in s || "hämtad" in s || "hamtad" in s || "lossad" in s -> shipment.copy(type = ShipmentType.DEL)
            else -> shipment
        }
    }

    fun classifyByRoles(shipment: Shipment, mottagare: String?, avsandare: String? = null): Shipment {
        val mott = mottagare?.lowercase().orEmpty()
        val avs = avsandare?.lowercase().orEmpty()
        val mottIsTerminal = TERMINAL_NAMES.any { it in mott } || TERMINAL_STREETS.any { it in mott }
        val avsIsTerminal = TERMINAL_NAMES.any { it in avs } || TERMINAL_STREETS.any { it in avs }
        return when {
            mottIsTerminal && !avsIsTerminal -> shipment.copy(type = ShipmentType.PICK)
            avsIsTerminal && !mottIsTerminal -> shipment.copy(type = ShipmentType.DEL)
            else -> {
                val stopIsTerminal = isTerminalAddress(shipment.address, shipment.recipient)
                if (stopIsTerminal) shipment.copy(type = ShipmentType.PICK)
                else shipment.copy(type = ShipmentType.DEL)
            }
        }
    }

    private fun recoverMissingStreet(name: String, street: String, number: String): Pair<String, String> {
        val st = street.trim()
        val num = number.trim()
        val looksMissing = st.isBlank() || st.all { it.isDigit() } || st.length <= 2
        if (!looksMissing) return st to num
        val place = name.substringBefore("/").trim()
        if (place.length < 4) return st to num
        val upper = place.uppercase().replace("Ö", "O").replace("Ä", "A").replace("Å", "A")
        val companySuffixes = listOf(" AB", " HB", " KB", " A/S", " AS", " GMBH", " LTD")
        val isCompany = companySuffixes.any { upper.endsWith(it.trim()) || upper.endsWith(it) }
        val placeHints = listOf("GARD", "GAARD", "GARDEN", "TORP", "ASPER", "OJEBRO")
        val looksLikePlace = placeHints.any { it in upper } || (!isCompany && " " in place)
        return if (looksLikePlace) place to num else st to num
    }

    private fun buildAddress(street: String, number: String): String =
        when {
            street.isBlank() -> number
            number.isBlank() -> street
            else -> "$street $number"
        }.trim()

    private fun parseWeight(raw: String): Int {
        if (raw.isBlank()) return 0
        val normalized = raw.replace(",", ".").replace(Regex("[^0-9.]"), "")
        return normalized.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0
    }

    private fun parseDelTimeWindow(a: String?, b: String?): Pair<Int?, Int?> {
        val ma = parseDateTimeToMinutes(a)
        val mb = parseDateTimeToMinutes(b)
        return when {
            ma != null && mb != null -> if (ma.first <= mb.first) ma.second to mb.second else mb.second to ma.second
            ma != null -> ma.second to null
            mb != null -> null to mb.second
            else -> null to null
        }
    }

    private fun parseDateTimeToMinutes(raw: String?): Pair<Long, Int>? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().trimEnd('\r', '\n')
        val full = Regex("""(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?""").matchEntire(s)
        if (full != null) {
            val y = full.groupValues[1].toInt()
            val mo = full.groupValues[2].toInt()
            val d = full.groupValues[3].toInt()
            val h = full.groupValues[4].toInt()
            val mi = full.groupValues[5].toInt()
            if (h !in 0..23 || mi !in 0..59) return null
            val key = y * 100000000L + mo * 1000000L + d * 10000L + h * 100L + mi
            return key to (h * 60 + mi)
        }
        val hm = Regex("""^(\\d{1,2}):(\\d{2})$""").matchEntire(s) ?: return null
        val h = hm.groupValues[1].toInt()
        val mi = hm.groupValues[2].toInt()
        if (h !in 0..23 || mi !in 0..59) return null
        return (h * 100L + mi) to (h * 60 + mi)
    }

    private fun cleanPortkod(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        s = s.replace(Regex("""^PK\\s*:?\\s*""", RegexOption.IGNORE_CASE), "")
        s = s.trim(' ', ',', ';')
        if (s.isBlank() || s.length > 20) return null
        if (s.any { it.isLetter() } && s.count { it.isDigit() } == 0) return null
        return s
    }

    private fun shortenCarrier(raw: String): String = CarrierNames.shorten(raw)

    private fun isTerminalAddress(streetOrAddress: String, name: String): Boolean {
        val s = streetOrAddress.lowercase()
        val n = name.lowercase()
        return TERMINAL_STREETS.any { it in s } || TERMINAL_NAMES.any { it in n }
    }

    private fun consolidateByRawId(list: List<Shipment>): List<Shipment> {
        val withId = list.filter { !it.rawId.isNullOrBlank() }
        val withoutId = list.filter { it.rawId.isNullOrBlank() }
        val grouped = withId.groupBy { it.rawId!! }
        val merged = grouped.map { (_, group) ->
            if (group.size == 1) group.first()
            else {
                val first = group.first()
                first.copy(
                    colliCount = group.sumOf { it.colliCount },
                    weightKg = group.maxOf { it.weightKg }.let { maxW ->
                        if (maxW > 0) maxW else group.sumOf { it.weightKg }
                    },
                    portkod = group.mapNotNull { it.portkod }.firstOrNull(),
                    comment = group.mapNotNull { it.comment }.firstOrNull(),
                    earliestMinutes = group.mapNotNull { it.earliestMinutes }.minOrNull() ?: first.earliestMinutes,
                    latestMinutes = group.mapNotNull { it.latestMinutes }.maxOrNull() ?: first.latestMinutes
                )
            }
        }
        return merged + withoutId
    }
}
