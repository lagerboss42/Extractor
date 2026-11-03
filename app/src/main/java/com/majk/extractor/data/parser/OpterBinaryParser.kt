package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.TextEncoding

/**
 * Parses Opter binary TLV list-load payloads (port 81).
 *
 * String TLV:
 *   0x04 | fieldId_u8 | length_u16_le | bytes
 *
 * Address field IDs:
 *   2  name, 6 street, 7 number, 8 zip, 9 city, 12 phone
 *
 * Stops appear as pairs: customer + Alltransport terminal.
 *   customer → terminal  = pickup
 *   terminal → customer  = delivery
 *
 * Fired when Opter is (re)started with VPN on — full route list-load.
 */
object OpterBinaryParser {

    private const val F_NAME = 2
    private const val F_STREET = 6
    private const val F_NUMBER = 7
    private const val F_ZIP = 8
    private const val F_CITY = 9

    private data class Tlv(val offset: Int, val id: Int, val value: String)

    private data class Addr(
        val offset: Int,
        val name: String,
        val street: String,
        val number: String,
        val zip: String,
        val city: String
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

    /** Safe byte-level parsing directly from the network stream. */
    fun parse(payloadBytes: ByteArray): List<Shipment> {
        if (payloadBytes.size < 40) return emptyList()
        val fields = extractTlvs(payloadBytes)
        if (fields.size < 4) return emptyList()

        val addrs = buildAddresses(fields)
        return pairToShipments(addrs)
    }

    /** StringBuffer / traffic-log fallback (ISO-8859-1 round-trip). */
    fun parse(payload: String): List<Shipment> {
        if (payload.length < 40) return emptyList()
        val data = payload.toByteArray(Charsets.ISO_8859_1)
        return parse(data)
    }

    private fun extractTlvs(data: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0x04.toByte()) {
                val id = data[i + 1].toInt() and 0xFF
                val len = (data[i + 2].toInt() and 0xFF) or ((data[i + 3].toInt() and 0xFF) shl 8)
                if (len in 1..200 && i + 4 + len <= data.size) {
                    val slice = data.copyOfRange(i + 4, i + 4 + len)
                    val bad = slice.count { b ->
                        val v = b.toInt() and 0xFF
                        v < 9 || v in 14..31
                    }
                    if (bad <= len / 3) {
                        val value = decodeTlvString(slice)
                        if (value.isNotBlank()) {
                            out.add(Tlv(i, id, value))
                        }
                        i += 4 + len
                        continue
                    }
                }
            }
            i++
        }
        return out
    }

    /** Wire strings are UTF-8; logs may contain Latin-1 mojibake — repair ÅÄÖ either way. */
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
            colliCount = 0,
            weightKg = 0,
            portkod = null,
            comment = null,
            carrier = "",
            sender = null
        )
    }
}
