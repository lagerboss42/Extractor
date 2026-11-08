package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.CarrierNames
import com.majk.extractor.util.TextEncoding
import java.util.regex.Pattern

/**
 * Parses Opter binary TLV list-load payloads (port 81).
 *
 * String TLV:
 *   0x04 | fieldId_u8 | length_u16_le | bytes
 *
 * Address field IDs:
 *   2  name, 6 street, 7 number, 8 zip, 9 city, 12 phone
 *
 * Free-text coordinates appear in message/info fields (prop ~70 / SHI_Message)
 * as plain text: "Johan:(+46) 70 300 82 72 58.544853, 15.633303"
 *
 * Stops appear as pairs: customer + Alltransport terminal.
 *   customer → terminal  = pickup
 *   terminal → customer  = delivery
 */
object OpterBinaryParser {

    private const val F_NAME = 2
    private const val F_STREET = 6
    private const val F_NUMBER = 7
    private const val F_ZIP = 8
    private const val F_CITY = 9

    // Coordinate patterns in free text (dot or comma decimal, various separators)
    private val COORD_PATTERN = Pattern.compile(
        """(5[6-9][.,]\d{4,8})\s*[,;\s]\s*(1[0-9][.,]\d{4,8})"""
    )

    private data class Tlv(val offset: Int, val id: Int, val value: String)

    private data class Addr(
        val offset: Int,
        val name: String,
        val street: String,
        val number: String,
        val zip: String,
        val city: String,
        var lat: Double? = null,
        var lon: Double? = null,
        var colli: Int = 0,
        var weight: Int = 0,
        var carrier: String = "",
        var comment: String? = null
    ) {
        val isTerminal: Boolean
            get() {
                val s = TextEncoding.foldAscii(street)
                val n = TextEncoding.foldAscii(name)
                return "kopetorpsgatan" in s || "kromgatan" in s || "alltransport" in n
            }

        val isJunk: Boolean
            get() {
                if (street.startsWith("Nekad", ignoreCase = true)) return true
                if (street.startsWith("08-")) return true
                if (zip.length != 5) return true
                if (street.length < 3) return true
                return false
            }
    }

    fun parse(payloadBytes: ByteArray): List<Shipment> {
        if (payloadBytes.size < 40) return emptyList()
        val fields = extractTlvs(payloadBytes)
        if (fields.size < 4) return emptyList()

        val addrs = buildAddresses(fields)
        attachFreeTextCoords(fields, addrs)
        attachColliWeightCarrier(fields, addrs)
        return pairToShipments(addrs)
    }

    fun parse(payload: String): List<Shipment> {
        return parse(payload.toByteArray(Charsets.ISO_8859_1))
    }

    private fun extractTlvs(data: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == 0x04.toByte()) {
                val id = data[i + 1].toInt() and 0xFF
                val len = (data[i + 2].toInt() and 0xFF) or ((data[i + 3].toInt() and 0xFF) shl 8)
                if (len in 1..200 && i + 4 + len <= data.size) {
                    val slice = data.copyOfRange(i + 4, i + 4 + len)
                    val value = decodeTlvString(slice)
                    if (value.isNotBlank()) {
                        out.add(Tlv(i, id, value))
                    }
                    i += 4 + len
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun decodeTlvString(slice: ByteArray): String {
        val asUtf8 = runCatching { String(slice, Charsets.UTF_8) }.getOrNull()
        val candidate = when {
            asUtf8 != null && !asUtf8.contains('\uFFFD') -> asUtf8
            else -> String(slice, Charsets.ISO_8859_1)
        }
        return TextEncoding.fixSwedish(candidate).trim()
    }

    private fun buildAddresses(fields: List<Tlv>): List<Addr> {
        val result = mutableListOf<Addr>()
        var i = 0
        while (i < fields.size) {
            val f = fields[i]
            if (f.id != F_STREET) {
                i++
                continue
            }
            var name = ""
            var j = i - 1
            while (j >= 0 && j >= i - 12) {
                if (fields[j].id == F_NAME) {
                    name = fields[j].value
                    break
                }
                j--
            }
            var number = ""
            var zip = ""
            var city = ""
            var k = i + 1
            while (k < fields.size && k <= i + 8) {
                when (fields[k].id) {
                    F_NUMBER -> if (number.isEmpty()) number = fields[k].value
                    F_ZIP -> if (zip.isEmpty()) zip = fields[k].value
                    F_CITY -> if (city.isEmpty()) city = fields[k].value
                    F_NAME, F_STREET -> break
                }
                k++
            }
            val zipClean = zip.filter { it.isDigit() }.take(5)
            val cityClean = city.trim()
            val nameClean = name.trim()
            // Empty is not junk — .all {} on empty is true in Kotlin.
            if (nameClean.isNotEmpty() && (nameClean.all { it.isDigit() } || nameClean.equals("true", true) || nameClean.equals("false", true))) {
                i++
                continue
            }
            if (cityClean.isNotEmpty() && (cityClean.equals("true", true) || cityClean.equals("false", true) || cityClean.all { it.isDigit() })) {
                i++
                continue
            }
            if (zipClean.length == 5 && f.value.length >= 3) {
                result.add(
                    Addr(
                        offset = f.offset,
                        name = name,
                        street = f.value,
                        number = number.trim(),
                        zip = zipClean,
                        city = cityClean
                    )
                )
            }
            i++
        }
        return result
    }

    private fun attachFreeTextCoords(fields: List<Tlv>, addrs: List<Addr>) {
        if (addrs.isEmpty()) return
        for (f in fields) {
            val m = COORD_PATTERN.matcher(f.value)
            if (!m.find()) continue
            val latStr = m.group(1).replace(',', '.')
            val lonStr = m.group(2).replace(',', '.')
            val lat = latStr.toDoubleOrNull() ?: continue
            val lon = lonStr.toDoubleOrNull() ?: continue
            if (lat in 58.414..58.416 && lon in 15.662..15.664) continue
            val nearest = addrs.minByOrNull { kotlin.math.abs(it.offset - f.offset) } ?: continue
            if (nearest.lat == null) {
                nearest.lat = lat
                nearest.lon = lon
            }
        }
    }

    private fun attachColliWeightCarrier(fields: List<Tlv>, addrs: List<Addr>) {
        if (addrs.isEmpty()) return
        for (a in addrs) {
            val nearby = fields.filter { kotlin.math.abs(it.offset - a.offset) < 1200 }
            for (f in nearby) {
                val v = f.value
                if (a.carrier.isEmpty()) {
                    val shortened = CarrierNames.shorten(v)
                    if (shortened.isNotBlank() && shortened != v.trim() && shortened.length < 20) {
                        a.carrier = shortened
                    }
                }
                if (a.colli == 0) {
                    val colliMatch = Regex("""(\d{1,3})\s*(?:kli|colli|st|pcs)""", RegexOption.IGNORE_CASE)
                        .find(v)
                    if (colliMatch != null) {
                        a.colli = colliMatch.groupValues[1].toIntOrNull() ?: 0
                    }
                }
                if (a.weight == 0) {
                    val weightMatch = Regex("""(\d+[.,]\d{1,3})\s*kg""", RegexOption.IGNORE_CASE)
                        .find(v)
                    if (weightMatch != null) {
                        val w = weightMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
                        if (w != null && w > 0) a.weight = kotlin.math.ceil(w).toInt()
                    }
                }
                if (a.comment == null && v.length in 5..120 && !COORD_PATTERN.matcher(v).find()) {
                    if (v.any { it.isLetter() } && !v.startsWith("1-") && !v.all { it.isDigit() }) {
                        a.comment = v
                    }
                }
            }
        }
    }

    private fun pairToShipments(addrs: List<Addr>): List<Shipment> {
        val out = mutableListOf<Shipment>()
        var i = 0
        while (i < addrs.size) {
            val a = addrs[i]
            val b = addrs.getOrNull(i + 1)

            when {
                b != null && !a.isTerminal && b.isTerminal && !a.isJunk -> {
                    out.add(toShipment(a, ShipmentType.PICK))
                    i += 2
                }
                b != null && a.isTerminal && !b.isTerminal && !b.isJunk -> {
                    out.add(toShipment(b, ShipmentType.DEL))
                    i += 2
                }
                !a.isTerminal && !a.isJunk -> {
                    out.add(toShipment(a, ShipmentType.DEL))
                    i += 1
                }
                else -> i += 1
            }
        }

        val seen = linkedSetOf<String>()
        return out.filter { s ->
            val key = "${s.type}|${s.zip}|${s.address.lowercase()}"
            seen.add(key)
        }
    }

    private fun toShipment(a: Addr, type: ShipmentType): Shipment {
        val address = when {
            a.street.isBlank() -> a.number
            a.number.isBlank() -> a.street
            else -> "${a.street} ${a.number}"
        }.trim()

        val recipient = a.name
            .let { if ("/" in it) it.substringBefore("/").trim() else it.trim() }
            .let { n ->
                when {
                    n.equals("UT", true) || n.equals("IN", true) -> ""
                    n.startsWith("TEST", true) -> ""
                    else -> n
                }
            }

        return Shipment(
            type = type,
            recipient = recipient,
            address = address,
            zip = a.zip,
            city = a.city,
            colliCount = a.colli,
            weightKg = a.weight,
            portkod = null,
            comment = a.comment,
            carrier = a.carrier,
            sender = null,
            lat = a.lat,
            lon = a.lon
        )
    }
}
