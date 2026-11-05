package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.CarrierNames
import com.majk.extractor.util.TextEncoding
import java.util.regex.Pattern
import kotlin.math.ceil

object OpterHttpParser {

    private val TERMINAL_STREETS = listOf("kopetorpsgatan", "kromgatan")
    private val TERMINAL_NAMES = listOf("alltransport")

    // Free-text coordinate pattern (dot or comma decimal)
    private val COORD_PATTERN = Pattern.compile(
        """(5[6-9][.,]\d{4,8})\s*[,;\s]\s*(1[0-9][.,]\d{4,8})"""
    )

    /**
     * Scan payload for free-text coordinates and attach to shipments that lack them.
     * Coordinates appear in message fields as plain text, e.g.:
     *   "Johan:(+46) 70 300 82 72 58.544853, 15.633303"
     */
    private fun attachFreeTextCoords(payload: String, shipments: MutableList<Shipment>) {
        if (shipments.isEmpty()) return
        val m = COORD_PATTERN.matcher(payload)
        val found = mutableListOf<Pair<Double, Double>>()
        while (m.find()) {
            val latStr = m.group(1).replace(',', '.')
            val lonStr = m.group(2).replace(',', '.')
            val lat = latStr.toDoubleOrNull() ?: continue
            val lon = lonStr.toDoubleOrNull() ?: continue
            // Skip terminal cluster (device position)
            if (lat in 58.414..58.416 && lon in 15.662..15.664) continue
            found.add(lat to lon)
        }
        if (found.isEmpty()) return
        // Attach to shipments that don't already have coords
        var idx = 0
        for (s in shipments) {
            if (s.lat != null) continue
            if (idx >= found.size) break
            val (lat, lon) = found[idx++]
            val updated = s.copy(lat = lat, lon = lon)
            val pos = shipments.indexOf(s)
            if (pos >= 0) shipments[pos] = updated
        }
    }

    fun parse(payloadBytes: ByteArray): List<Shipment> {
        if (payloadBytes.isEmpty()) return emptyList()
        val asLatin1 = String(payloadBytes, Charsets.ISO_8859_1)
        val results = mutableListOf<Shipment>()
        results += parseDelRecords(asLatin1)
        results += parseEmbeddedAddresses(asLatin1)
        results += OpterBinaryParser.parse(payloadBytes)
        attachFreeTextCoords(asLatin1, results)
        return results
    }

    fun parse(payload: String): List<Shipment> {
        val results = mutableListOf<Shipment>()
        results += parseDelRecords(payload)
        results += parseEmbeddedAddresses(payload)
        results += OpterBinaryParser.parse(payload)
        attachFreeTextCoords(payload, results)
        return results
    }

    fun containsDelMarker(payload: String): Boolean =
        payload.contains("DEL\t") || Regex("""(?m)^DEL[\t ]""").containsMatchIn(payload)

    private fun parseDelRecords(payload: String): List<Shipment> {
        val shipments = mutableListOf<Shipment>()
        val normalized = payload.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
        val lines = mutableListOf<String>()
        val em = Pattern.compile("""DEL\t([^\n]{30,})""").matcher(normalized)
        while (em.find()) lines.add("DEL\t" + em.group(1).trimEnd())
        for (raw in normalized.split('\n')) {
            if (raw.contains("DEL\t")) {
                val t = "DEL\t" + raw.substringAfter("DEL\t").trim()
                if (t.length > 40) lines.add(t)
            }
        }
        val seenLine = mutableSetOf<String>()
        for (line in lines) {
            if (!seenLine.add(line.take(120))) continue
            val fields = line.split("\t")
            if (fields.size < 30) continue
            try {
                val shipmentId = fields.getOrNull(8)?.trim().orEmpty()
                val recipientName = TextEncoding.fixSwedish(fields.getOrNull(10)?.trim().orEmpty())
                val recipientCity = TextEncoding.fixSwedish(fields.getOrNull(11)?.trim().orEmpty())
                val senderName = TextEncoding.fixSwedish(fields.getOrNull(12)?.trim().orEmpty())
                val senderCity = TextEncoding.fixSwedish(fields.getOrNull(13)?.trim().orEmpty())
                val senderStreet = TextEncoding.fixSwedish(fields.getOrNull(21)?.trim().orEmpty())
                val senderNumber = fields.getOrNull(22)?.trim().orEmpty()
                val senderZip = fields.getOrNull(23)?.trim()?.filter { it.isDigit() }?.take(5).orEmpty()
                val recipientStreet = TextEncoding.fixSwedish(fields.getOrNull(26)?.trim().orEmpty())
                val recipientNumber = fields.getOrNull(27)?.trim().orEmpty()
                val recipientZip = fields.getOrNull(28)?.trim()?.filter { it.isDigit() }?.take(5).orEmpty()
                val carrierRaw = TextEncoding.fixSwedish(fields.getOrNull(33)?.trim().orEmpty())
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
                    recipientZip.length == 5 ->
                        Tuple7(ShipmentType.DEL, recipientName, recipientStreet, recipientNumber, recipientZip, recipientCity, senderName)
                    senderZip.length == 5 ->
                        Tuple7(ShipmentType.PICK, senderName, senderStreet, senderNumber, senderZip, senderCity, recipientName)
                    else -> null
                } ?: continue

                if (resolved.name.isBlank() && resolved.street.isBlank()) continue
                if (resolved.zip.length != 5 && resolved.street.isBlank()) continue

                val (recStreet, recNumber) = recoverMissingStreet(resolved.name, resolved.street, resolved.number)
                val address = buildAddress(recStreet, recNumber)
                if (address.isBlank() && resolved.zip.length != 5) continue

                val colli = colliRaw.toIntOrNull()?.coerceAtLeast(0) ?: 1
                val weight = parseWeight(weightRaw)
                val carrier = CarrierNames.shorten(carrierRaw)
                val (earliestMin, latestMin) = parseDelTimeWindow(fields.getOrNull(49), fields.getOrNull(58))

                shipments.add(
                    Shipment(
                        type = resolved.type,
                        recipient = resolved.name.ifBlank { address },
                        address = address.ifBlank { resolved.name },
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
        // Hyphen at end of class so it is literal, not a range
        val pattern = Pattern.compile(
            """([A-Za-zÅÄÖåäö0-9 /&.,-]{4,80})\t+([A-Za-zÅÄÖåäö0-9 ]{2,40})\t+(\\d{0,4}[A-Za-z]?)\t+(\\d{5})\t+([A-Za-zÅÄÖåäö]{2,25})"""
        )
        val matcher = pattern.matcher(normalized)
        val seen = mutableSetOf<String>()
        while (matcher.find()) {
            val name = TextEncoding.fixSwedish(matcher.group(1).trim())
            val street = TextEncoding.fixSwedish(matcher.group(2).trim())
            val number = matcher.group(3).trim()
            val zip = matcher.group(4).trim()
            val city = TextEncoding.fixSwedish(matcher.group(5).trim())
            if (name.length < 4) continue
            if (street.lowercase() in listOf("sweden", "false", "true", "standard")) continue
            if (!zip.matches(Regex("\\d{5}"))) continue
            val key = "\$name|\$street|\$zip"
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
            "bek\u00e4ftad" in s || "bekraftad" in s -> shipment.copy(type = ShipmentType.PICK)
            "lastad" in s || "h\u00e4mtad" in s || "hamtad" in s || "lossad" in s -> shipment.copy(type = ShipmentType.DEL)
            else -> shipment
        }
    }

    fun classifyByRoles(shipment: Shipment, mottagare: String?, avsandare: String? = null): Shipment {
        val mott = TextEncoding.foldAscii(mottagare.orEmpty())
        val avs = TextEncoding.foldAscii(avsandare.orEmpty())
        val mottIsTerminal = TERMINAL_NAMES.any { it in mott } || TERMINAL_STREETS.any { it in mott }
        val avsIsTerminal = TERMINAL_NAMES.any { it in avs } || TERMINAL_STREETS.any { it in avs }
        return when {
            mottIsTerminal && !avsIsTerminal -> shipment.copy(type = ShipmentType.PICK)
            avsIsTerminal && !mottIsTerminal -> shipment.copy(type = ShipmentType.DEL)
            else -> {
                val stopIsTerminal = isTerminalAddress(shipment.address, shipment.recipient)
                if (stopIsTerminal) shipment.copy(type = ShipmentType.PICK) else shipment
            }
        }
    }

    private fun buildAddress(street: String, number: String): String {
        return when {
            street.isBlank() -> number
            number.isBlank() -> street
            else -> "\$street \$number"
        }.trim()
    }

    private fun recoverMissingStreet(name: String, street: String, number: String): Pair<String, String> {
        if (street.isNotBlank()) return street to number
        return name to number
    }

    private fun parseWeight(raw: String): Int {
        if (raw.isBlank()) return 0
        val cleaned = raw.replace(',', '.').trim()
        val d = cleaned.toDoubleOrNull() ?: return 0
        return kotlin.math.ceil(d).toInt().coerceAtLeast(0)
    }

    private fun parseDelTimeWindow(earliest: String?, latest: String?): Pair<Int?, Int?> {
        fun toMin(s: String?): Int? {
            if (s.isNullOrBlank()) return null
            val m = Regex("""(\\d{1,2}):(\\d{2})""").find(s) ?: return null
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h !in 0..23 || mi !in 0..59) return null
            return h * 60 + mi
        }
        return toMin(earliest) to toMin(latest)
    }

    private fun isTerminalAddress(streetOrAddress: String, name: String): Boolean {
        val s = TextEncoding.foldAscii(streetOrAddress)
        val n = TextEncoding.foldAscii(name)
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
                    weightKg = group.sumOf { it.weightKg },
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
