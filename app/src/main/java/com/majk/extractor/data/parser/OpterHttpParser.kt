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

    /**
     * DEL column indices. Wire format is positional (tab-separated), matching
     * Opter Shipment fields: package identity, recipient, status, weight, colli.
     * Centralized so a schema change is one edit, not scattered magic numbers.
     */
    private object DelCol {
        const val PACKAGE_ID_A = 5
        const val PACKAGE_ID_B = 7
        const val PACKAGE_ID_C = 9
        const val SHIPMENT_ID = 8
        const val RECIPIENT_NAME = 10
        const val RECIPIENT_CITY = 11
        const val SENDER_NAME = 12
        const val SENDER_CITY = 13
        const val STATUS = 16
        const val SENDER_STREET = 21
        const val SENDER_NUMBER = 22
        const val SENDER_ZIP = 23
        const val RECIPIENT_STREET = 26
        const val RECIPIENT_NUMBER = 27
        const val RECIPIENT_ZIP = 28
        const val CARRIER = 33
        const val WEIGHT_A = 39
        const val WEIGHT_B = 40
        const val COLLI = 45
        const val TIME_A = 49
        const val TIME_B = 58
    }

    fun parse(payload: String): List<Shipment> {
        val del = parseDelRecords(payload)
        val embedded = parseEmbeddedAddresses(payload)
        val binary = OpterBinaryParser.parse(payload)

        // Prefer DEL. Binary/embedded are fallback only.
        // If DEL produced results, only add extras that carry a package ID we don't already have.
        // If DEL produced nothing, fall back to binary/embedded (junk-filtered).
        fun isJunk(s: Shipment): Boolean {
            val name = s.recipient.trim()
            val city = s.city.trim()
            // Empty is not junk — Region rows can have a blank name after cleaning.
            // all {} on empty is true in Kotlin, so require non-empty first.
            if (name.isNotEmpty() && (name.all { it.isDigit() } || name.equals("true", true) || name.equals("false", true))) return true
            if (city.isNotEmpty() && (city.equals("true", true) || city.equals("false", true) || city.all { it.isDigit() })) return true
            return false
        }
        if (del.isNotEmpty()) {
            val seenIds = del.mapNotNull { it.rawId }.filter { it.isNotBlank() }.toSet()
            val extras = (embedded + binary).filter { s ->
                if (isJunk(s)) return@filter false
                val id = s.rawId
                !id.isNullOrBlank() && id !in seenIds
            }
            return del + extras
        }
        return (embedded + binary).filter { !isJunk(it) }
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
            val t = raw.trim()
            if (t.startsWith("DEL\t") && t.length > 40) lines.add(t)
        }
        val seenLine = mutableSetOf<String>()
        for (line in lines) {
            // Include package-level ID in the dedupe key so distinct colli are never dropped.
            val fields = line.split("\t")
            val pkgKey = fields.getOrNull(DelCol.PACKAGE_ID_A)?.trim().orEmpty()
                .ifBlank { fields.getOrNull(DelCol.PACKAGE_ID_B)?.trim().orEmpty() }
                .ifBlank { fields.getOrNull(DelCol.PACKAGE_ID_C)?.trim().orEmpty() }
            val lineKey = if (pkgKey.isNotBlank()) "$pkgKey|${line.take(80)}" else line.take(120)
            if (!seenLine.add(lineKey)) continue
            if (fields.size < 30) continue
            try {
                val shipmentId = fields.getOrNull(DelCol.SHIPMENT_ID)?.trim().orEmpty()
                val packageId = fields.getOrNull(DelCol.PACKAGE_ID_A)?.trim().orEmpty()
                    .ifBlank { fields.getOrNull(DelCol.PACKAGE_ID_B)?.trim().orEmpty() }
                    .ifBlank { fields.getOrNull(DelCol.PACKAGE_ID_C)?.trim().orEmpty() }
                val recipientName = TextEncoding.fixSwedish(fields.getOrNull(DelCol.RECIPIENT_NAME)?.trim().orEmpty())
                val recipientCity = TextEncoding.fixSwedish(fields.getOrNull(DelCol.RECIPIENT_CITY)?.trim().orEmpty())
                val senderName = TextEncoding.fixSwedish(fields.getOrNull(DelCol.SENDER_NAME)?.trim().orEmpty())
                val senderCity = TextEncoding.fixSwedish(fields.getOrNull(DelCol.SENDER_CITY)?.trim().orEmpty())
                val senderStreet = TextEncoding.fixSwedish(fields.getOrNull(DelCol.SENDER_STREET)?.trim().orEmpty())
                val senderNumber = fields.getOrNull(DelCol.SENDER_NUMBER)?.trim().orEmpty()
                val senderZip = fields.getOrNull(DelCol.SENDER_ZIP)?.trim()?.filter { it.isDigit() }?.take(5).orEmpty()
                val recipientStreet = TextEncoding.fixSwedish(fields.getOrNull(DelCol.RECIPIENT_STREET)?.trim().orEmpty())
                val recipientNumber = fields.getOrNull(DelCol.RECIPIENT_NUMBER)?.trim().orEmpty()
                val recipientZip = fields.getOrNull(DelCol.RECIPIENT_ZIP)?.trim()?.filter { it.isDigit() }?.take(5).orEmpty()
                val carrierRaw = TextEncoding.fixSwedish(fields.getOrNull(DelCol.CARRIER)?.trim().orEmpty())
                val weightRaw = fields.getOrNull(DelCol.WEIGHT_B)?.trim().orEmpty()
                    .ifBlank { fields.getOrNull(DelCol.WEIGHT_A)?.trim().orEmpty() }
                val colliRaw = fields.getOrNull(DelCol.COLLI)?.trim().orEmpty()

                val statusField = fields.getOrNull(DelCol.STATUS)?.trim().orEmpty().lowercase()
                val nearbyText = fields.drop(10).take(40).joinToString(" ").lowercase()
                val isFailed = statusField.contains("nekad") ||
                    nearbyText.contains("dex07") || nearbyText.contains("dex08") || nearbyText.contains("dex17") ||
                    nearbyText.contains("nekad leverans") || nearbyText.contains("ingen på plats") ||
                    nearbyText.contains("stängt") || nearbyText.contains("ombokar")
                if (isFailed) continue

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
                val (earliestMin, latestMin) = parseDelTimeWindow(
                    fields.getOrNull(DelCol.TIME_A),
                    fields.getOrNull(DelCol.TIME_B)
                )

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
                        rawId = (packageId.ifBlank { shipmentId }).ifBlank { null },
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
            """([A-Za-zÅÄÖåäö0-9 /&\-.,]{4,80})\t+([A-Za-zÅÄÖåäö0-9 ]{2,40})\t+(\d{0,4}[A-Za-z]?)\t+(\d{5})\t+([A-Za-zÅÄÖåäö]{2,25})"""
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

    private fun recoverMissingStreet(name: String, street: String, number: String): Pair<String, String> {
        val st = street.trim()
        val num = number.trim()
        val looksMissing = st.isBlank() || st.all { it.isDigit() } || st.length <= 2
        if (!looksMissing) return st to num
        val place = name.substringBefore("/").trim()
        if (place.length < 4) return st to num
        val upper = TextEncoding.foldAscii(place).uppercase()
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
        return normalized.toDoubleOrNull()?.let { v ->
            if (v <= 0.0) 0 else ceil(v - 1e-9).toInt()
        } ?: 0
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
        val full = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?""").matchEntire(s)
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
        val hm = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(s) ?: return null
        val h = hm.groupValues[1].toInt()
        val mi = hm.groupValues[2].toInt()
        if (h !in 0..23 || mi !in 0..59) return null
        return (h * 100L + mi) to (h * 60 + mi)
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
