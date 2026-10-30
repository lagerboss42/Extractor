package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import java.nio.charset.Charset

/**
 * Parses Opter binary TLV list-load payloads (port 81).
 *
 * String TLV:
 *   0x04 | fieldId_u8 | length_u16_le | utf8_bytes
 *
 * Address field IDs:
 *   2  name, 6 street, 7 number, 8 zip, 9 city, 12 phone, 24 label
 *
 * Stops appear as **pairs**: customer address + Alltransport terminal.
 * Order of the pair determines type (verified against live manifests):
 *   customer → terminal  = **pickup**
 *   terminal → customer  = **delivery**
 */
object OpterBinaryParser {

    private val UTF8: Charset = Charsets.UTF_8

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
                val s = street.lowercase()
                val n = name.lowercase()
                return "köpetorp" in s || "kopetorp" in s || "kromgatan" in s ||
                    "alltransport" in n
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

    fun parse(payload: String): List<Shipment> {
        val data = payload.toByteArray(Charsets.ISO_8859_1)
        val fields = extractTlvs(data)
        if (fields.isEmpty()) return emptyList()

        val addrs = buildAddresses(fields)
        return pairToShipments(addrs)
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
                    if (bad <= 2) {
                        val s = try {
                            String(slice, UTF8)
                        } catch (_: Exception) {
                            String(slice, Charsets.ISO_8859_1)
                        }.trim()
                        if (s.isNotEmpty() && s.any { it.isLetterOrDigit() }) {
                            out.add(Tlv(i, id, s))
                            i += 4 + len
                            continue
                        }
                    }
                }
            }
            i++
        }
        return out
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
            for (j in i - 1 downTo maxOf(0, i - 6)) {
                if (fields[j].id == F_NAME) {
                    name = fields[j].value
                    break
                }
            }

            var number = ""
            var zip = ""
            var city = ""
            for (j in i + 1 until minOf(fields.size, i + 10)) {
                when (fields[j].id) {
                    F_NUMBER -> if (number.isEmpty()) number = fields[j].value
                    F_ZIP -> if (zip.isEmpty()) zip = fields[j].value.filter { it.isDigit() }.take(5)
                    F_CITY -> if (city.isEmpty()) city = fields[j].value
                    F_NAME, F_STREET -> break
                }
                if (fields[j].offset - f.offset > 80) break
            }

            val zipClean = zip.filter { it.isDigit() }.take(5)
            val cityClean = city.replace(Regex("""\\s+\\d+$"""), "").trim()
            result.add(
                Addr(
                    offset = f.offset,
                    name = name,
                    street = f.value,
                    number = number,
                    zip = zipClean,
                    city = cityClean
                )
            )
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
