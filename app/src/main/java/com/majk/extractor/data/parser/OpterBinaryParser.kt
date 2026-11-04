package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.CarrierNames
import com.majk.extractor.util.TextEncoding
import com.majk.extractor.util.WeightUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Parses Opter binary sync payloads (port 81) using the real SyncBase wire format
 * reverse-engineered from decompiled Opter source (SyncBase / Shipment / Address /
 * FreeTextMessage).
 *
 * BinaryDataType (ordinal = type byte):
 *   1 StartTable, 2 EndTable, 3 EndData,
 *   4 String (prop u8 + len u16le + UTF-8),
 *   5 Int32, 6 DateTime (seconds from 2000-01-01), 7 Bool,
 *   12–17 Decimal variants, 18 StringNull, 19 ByteArray
 *
 * Address.PropertyNumber (key):
 *   2 AddrLine1, 5 Company, 6 Street, 7 StreetNo, 8 Zip, 9 City,
 *   11 EntreCode, 17 Info, 18 Longitude, 19 Latitude
 *
 * Shipment.PropertyNumber (key):
 *   16/17 earliest/latest pickup, 18/19 earliest/latest delivery,
 *   20 Customer, 43 Weight, 51 Packages, 53 Message,
 *   141 SHI_TRA_Name, 142 DEL_TRA_Name
 *
 * FreeTextMessage.PropertyNumber:
 *   6 FTM_Text — often holds entrecode and/or coordinates shown at the
 *     bottom of the shipment detail screen.
 */
object OpterBinaryParser {

    private const val T_START_TABLE = 1
    private const val T_END_TABLE = 2
    private const val T_END_DATA = 3
    private const val T_STRING = 4
    private const val T_INT32 = 5
    private const val T_DATETIME = 6
    private const val T_BOOL = 7
    private const val T_DECIMAL_NULL = 8
    private const val T_INT32_NULL = 9
    private const val T_BOOL_NULL = 10
    private const val T_DATETIME_NULL = 11
    private const val T_DEC_I16 = 12
    private const val T_DEC_I16_ND = 13
    private const val T_DEC_I32 = 14
    private const val T_DEC_I32_ND = 15
    private const val T_DEC_I64 = 16
    private const val T_DEC_I64_ND = 17
    private const val T_STRING_NULL = 18
    private const val T_BYTE_ARRAY = 19

    private const val TABLE_ADDRESS = 1
    private const val TABLE_SHIPMENT = 12
    private const val TABLE_FREETEXT = 33

    private const val ADR_LINE1 = 2
    private const val ADR_COMPANY = 5
    private const val ADR_STREET = 6
    private const val ADR_STREETNO = 7
    private const val ADR_ZIP = 8
    private const val ADR_CITY = 9
    private const val ADR_ENTRE = 11
    private const val ADR_INFO = 17
    private const val ADR_LON = 18
    private const val ADR_LAT = 19

    private const val SHI_EARLIEST_PICK = 16
    private const val SHI_LATEST_PICK = 17
    private const val SHI_EARLIEST_DEL = 18
    private const val SHI_LATEST_DEL = 19
    private const val SHI_CUSTOMER = 20
    private const val SHI_WEIGHT = 43
    private const val SHI_PACKAGES = 51
    private const val SHI_MESSAGE = 53
    private const val SHI_CALC_WEIGHT = 90
    private const val SHI_CALC_PACKAGES = 96
    private const val SHI_LATEST_DEL_DT = 124
    private const val SHI_EARLIEST_DEL_DT = 127
    private const val SHI_TRA_NAME = 141
    private const val DEL_TRA_NAME = 142

    private const val FTM_TEXT = 6

    private data class Field(val table: Int, val prop: Int, val kind: Kind, val s: String?, val n: Double?, val t: Int?)
    private enum class Kind { STR, NUM, TIME }

    private data class Addr(
        val name: String,
        val street: String,
        val number: String,
        val zip: String,
        val city: String,
        val entre: String?,
        val info: String?,
        val lat: Double?,
        val lon: Double?
    ) {
        val isTerminal: Boolean
            get() {
                val s = TextEncoding.foldAscii(street)
                val n = TextEncoding.foldAscii(name)
                return "kopetorpsgatan" in s || "kromgatan" in s || "alltransport" in n
            }
        val isJunk: Boolean
            get() = zip.length != 5 || street.length < 3 ||
                street.startsWith("Nekad", true) || street.startsWith("08-")
    }

    private data class ShipMeta(
        val weight: Int,
        val packages: Int,
        val earliest: Int?,
        val latest: Int?,
        val carrier: String,
        val message: String?
    )

    private data class FreeNote(val text: String, val lat: Double?, val lon: Double?, val code: String?)

    fun parse(payloadBytes: ByteArray): List<Shipment> {
        if (payloadBytes.size < 40) return emptyList()
        val fields = walk(payloadBytes)
        if (fields.isEmpty()) return emptyList()

        val addrs = clusterAddresses(fields)
        val metas = collectShipmentMeta(fields)
        val notes = collectFreeText(fields)

        return pairAndEnrich(addrs, metas, notes)
    }

    fun parse(payload: String): List<Shipment> {
        if (payload.length < 40) return emptyList()
        return parse(payload.toByteArray(Charsets.ISO_8859_1))
    }

    private fun walk(data: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        var table = -1
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        while (i < data.size - 1) {
            val type = data[i].toInt() and 0xFF
            when (type) {
                T_START_TABLE -> {
                    if (i + 1 < data.size) {
                        table = data[i + 1].toInt() and 0xFF
                        i += 2
                        continue
                    }
                }
                T_END_TABLE -> {
                    if (i + 1 < data.size) {
                        table = -1
                        i += 2
                        continue
                    }
                }
                T_END_DATA -> {
                    i += 1 + 16
                    continue
                }
                T_STRING -> {
                    if (i + 4 <= data.size) {
                        val prop = data[i + 1].toInt() and 0xFF
                        val len = (data[i + 2].toInt() and 0xFF) or ((data[i + 3].toInt() and 0xFF) shl 8)
                        if (len in 0..4000 && i + 4 + len <= data.size) {
                            val slice = data.copyOfRange(i + 4, i + 4 + len)
                            val s = decodeString(slice)
                            if (s.isNotBlank()) out.add(Field(table, prop, Kind.STR, s, null, null))
                            i += 4 + len
                            continue
                        }
                    }
                }
                T_INT32 -> {
                    if (i + 6 <= data.size) {
                        val prop = data[i + 1].toInt() and 0xFF
                        val v = bb.getInt(i + 2)
                        out.add(Field(table, prop, Kind.NUM, null, v.toDouble(), null))
                        i += 6
                        continue
                    }
                }
                T_DATETIME -> {
                    if (i + 6 <= data.size) {
                        val prop = data[i + 1].toInt() and 0xFF
                        val sec = bb.getInt(i + 2)
                        out.add(Field(table, prop, Kind.TIME, null, null, sec))
                        i += 6
                        continue
                    }
                }
                T_BOOL -> {
                    if (i + 3 <= data.size) { i += 3; continue }
                }
                T_DEC_I16, T_DEC_I16_ND -> {
                    val hasScale = type == T_DEC_I16
                    val need = 2 + (if (hasScale) 1 else 0) + 2
                    if (i + need <= data.size) {
                        val prop = data[i + 1].toInt() and 0xFF
                        var p = i + 2
                        val scale = if (hasScale) data[p++].toInt() and 0xFF else 0
                        val raw = bb.getShort(p).toInt()
                        val n = raw / pow10(scale)
                        out.add(Field(table, prop, Kind.NUM, null, n, null))
                        i += need
                        continue
                    }
                }
                T_DEC_I32, T_DEC_I32_ND -> {
                    val hasScale = type == T_DEC_I32
                    val need = 2 + (if (hasScale) 1 else 0) + 4
                    if (i + need <= data.size) {
                        val prop = data[i + 1].toInt() and 0xFF
                        var p = i + 2
                        val scale = if (hasScale) data[p++].toInt() and 0xFF else 0
                        val raw = bb.getInt(p)
                        val n = raw / pow10(scale)
                        out.add(Field(table, prop, Kind.NUM, null, n, null))
                        i += need
                        continue
                    }
                }
                T_DEC_I64, T_DEC_I64_ND -> {
                    val hasScale = type == T_DEC_I64
                    val need = 2 + (if (hasScale) 1 else 0) + 8
                    if (i + need <= data.size) {
                        val prop = data[i + 1].toInt() and 0xFF
                        var p = i + 2
                        val scale = if (hasScale) data[p++].toInt() and 0xFF else 0
                        val raw = bb.getLong(p)
                        val n = raw / pow10(scale)
                        out.add(Field(table, prop, Kind.NUM, null, n, null))
                        i += need
                        continue
                    }
                }
                T_STRING_NULL, T_INT32_NULL, T_BOOL_NULL, T_DATETIME_NULL, T_DECIMAL_NULL -> {
                    if (i + 2 <= data.size) { i += 2; continue }
                }
                T_BYTE_ARRAY -> {
                    if (i + 6 <= data.size) {
                        val len = bb.getInt(i + 2)
                        if (len in 0..100_000 && i + 6 + len <= data.size) {
                            i += 6 + len
                            continue
                        }
                    }
                }
            }
            i++
        }
        return out
    }

    private fun pow10(scale: Int): Double {
        var s = 1.0
        repeat(scale.coerceIn(0, 8)) { s *= 10.0 }
        return s
    }

    private fun decodeString(slice: ByteArray): String {
        val asUtf8 = runCatching { String(slice, Charsets.UTF_8) }.getOrNull()
        val candidate = when {
            asUtf8 != null && !asUtf8.contains('\uFFFD') -> asUtf8
            else -> String(slice, Charsets.ISO_8859_1)
        }
        return TextEncoding.fixSwedish(candidate).trim()
    }

    private fun clusterAddresses(fields: List<Field>): List<Addr> {
        val result = mutableListOf<Addr>()
        val strings = fields.filter { it.kind == Kind.STR && (it.table == TABLE_ADDRESS || it.table == -1) }
        val nums = fields.filter { it.kind == Kind.NUM && (it.table == TABLE_ADDRESS || it.table == -1) }

        var i = 0
        while (i < strings.size) {
            val f = strings[i]
            if (f.prop != ADR_STREET && f.prop != ADR_LINE1) { i++; continue }

            val window = strings.subList(i, minOf(i + 14, strings.size))
            val name = window.firstOrNull { it.prop == ADR_LINE1 || it.prop == ADR_COMPANY }?.s.orEmpty()
            val street = window.firstOrNull { it.prop == ADR_STREET }?.s.orEmpty()
            val number = window.firstOrNull { it.prop == ADR_STREETNO }?.s.orEmpty()
            val zip = window.firstOrNull { it.prop == ADR_ZIP }?.s.orEmpty().filter { it.isDigit() }.take(5)
            val city = window.firstOrNull { it.prop == ADR_CITY }?.s.orEmpty()
            val entre = window.firstOrNull { it.prop == ADR_ENTRE }?.s
            val info = window.firstOrNull { it.prop == ADR_INFO }?.s

            val lat = nums.firstOrNull { it.prop == ADR_LAT && abs(it.n ?: 0.0) in 50.0..70.0 }?.n
            val lon = nums.firstOrNull { it.prop == ADR_LON && abs(it.n ?: 0.0) in 10.0..25.0 }?.n

            if (zip.length == 5 && street.length >= 3) {
                result.add(Addr(name, street, number, zip, city, entre, info, lat, lon))
            }
            i++
        }
        return result
    }

    private fun collectShipmentMeta(fields: List<Field>): List<ShipMeta> {
        val metas = mutableListOf<ShipMeta>()
        var weight = 0.0
        var packages = 0
        var earliest: Int? = null
        var latest: Int? = null
        var carrier = ""
        var message: String? = null
        var seen = false

        fun flush() {
            if (!seen) return
            metas.add(ShipMeta(
                weight = WeightUtils.roundToWhole(weight),
                packages = packages,
                earliest = earliest,
                latest = latest,
                carrier = CarrierNames.shorten(carrier),
                message = message
            ))
            weight = 0.0; packages = 0; earliest = null; latest = null; carrier = ""; message = null; seen = false
        }

        for (f in fields) {
            if (f.table != TABLE_SHIPMENT && f.table != -1) continue
            when (f.prop) {
                SHI_CUSTOMER -> { if (seen) flush(); seen = true }
                SHI_WEIGHT, SHI_CALC_WEIGHT -> {
                    seen = true
                    if ((f.n ?: 0.0) > weight) weight = f.n ?: 0.0
                }
                SHI_PACKAGES, SHI_CALC_PACKAGES -> {
                    seen = true
                    val p = (f.n ?: 0.0).toInt()
                    if (p > packages) packages = p
                }
                SHI_EARLIEST_DEL, SHI_EARLIEST_DEL_DT, SHI_EARLIEST_PICK -> {
                    seen = true
                    f.t?.let { earliest = secondsToMinutes(it) }
                }
                SHI_LATEST_DEL, SHI_LATEST_DEL_DT, SHI_LATEST_PICK -> {
                    seen = true
                    f.t?.let { latest = secondsToMinutes(it) }
                }
                SHI_TRA_NAME, DEL_TRA_NAME -> {
                    seen = true
                    if (f.s != null && carrier.isBlank()) carrier = f.s
                }
                SHI_MESSAGE -> {
                    seen = true
                    if (!f.s.isNullOrBlank()) message = f.s
                }
            }
        }
        flush()
        return metas
    }

    private fun secondsToMinutes(sec: Int): Int? {
        if (sec <= 0) return null
        val day = (sec % 86400)
        val m = day / 60
        return if (m in 0..1439) m else null
    }

    private fun collectFreeText(fields: List<Field>): List<FreeNote> {
        val notes = mutableListOf<FreeNote>()
        for (f in fields) {
            if (f.kind != Kind.STR || f.s.isNullOrBlank()) continue
            val isFtm = f.table == TABLE_FREETEXT && f.prop == FTM_TEXT
            val looksLikeNote = f.s.length >= 4 && (
                f.s.contains("kod", true) ||
                f.s.contains("port", true) ||
                Regex("""\d{2}\.\d{3,}""").containsMatchIn(f.s) ||
                isFtm
            )
            if (!isFtm && !looksLikeNote) continue

            val coords = extractCoords(f.s)
            val code = extractCode(f.s)
            if (coords != null || code != null || isFtm) {
                notes.add(FreeNote(f.s, coords?.first, coords?.second, code))
            }
        }
        return notes
    }

    private fun extractCoords(text: String): Pair<Double, Double>? {
        val m = Regex("""(\d{2}[.,]\d{3,})\s*[,;\s]\s*(\d{2}[.,]\d{3,})""").find(text) ?: return null
        val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in 56.0..60.0 || lon !in 11.0..19.5) return null
        return lat to lon
    }

    private fun extractCode(text: String): String? {
        val m = Regex("""(?:port\s*kod|entre\s*kod|kod)\s*[:=]?\s*([A-Za-z0-9]{3,8})""", RegexOption.IGNORE_CASE)
            .find(text)
        return m?.groupValues?.get(1)
    }

    private fun pairAndEnrich(addrs: List<Addr>, metas: List<ShipMeta>, notes: List<FreeNote>): List<Shipment> {
        val raw = mutableListOf<Pair<Addr, ShipmentType>>()
        var i = 0
        while (i < addrs.size) {
            val a = addrs[i]
            val b = addrs.getOrNull(i + 1)
            when {
                b != null && !a.isTerminal && b.isTerminal && !a.isJunk -> {
                    raw.add(a to ShipmentType.PICK); i += 2
                }
                b != null && a.isTerminal && !b.isTerminal && !b.isJunk -> {
                    raw.add(b to ShipmentType.DEL); i += 2
                }
                !a.isTerminal && !a.isJunk -> {
                    raw.add(a to ShipmentType.DEL); i += 1
                }
                else -> i += 1
            }
        }

        val out = mutableListOf<Shipment>()
        for ((idx, pair) in raw.withIndex()) {
            val (a, type) = pair
            val meta = metas.getOrNull(idx)
            val note = notes.getOrNull(idx)

            val address = listOf(a.street, a.number).filter { it.isNotBlank() }.joinToString(" ")
            val recipient = cleanName(a.name)
            val portkod = a.entre?.takeIf { it.isNotBlank() } ?: note?.code
            val commentParts = listOfNotNull(a.info, meta?.message, note?.text.takeUnless { it == null || it == portkod })
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val comment = commentParts.joinToString(" | ").ifBlank { null }

            val lat = a.lat ?: note?.lat
            val lon = a.lon ?: note?.lon

            out.add(Shipment(
                type = type,
                recipient = recipient,
                address = address,
                zip = a.zip,
                city = a.city,
                colliCount = meta?.packages ?: 0,
                weightKg = meta?.weight ?: 0,
                portkod = portkod,
                comment = comment,
                carrier = meta?.carrier.orEmpty(),
                sender = null,
                lat = lat,
                lon = lon,
                earliestMinutes = meta?.earliest,
                latestMinutes = meta?.latest
            ))
        }

        val seen = linkedSetOf<String>()
        return out.filter { s ->
            val key = "${s.type}|${s.zip}|${s.address.lowercase()}"
            seen.add(key)
        }
    }

    private fun cleanName(n: String): String {
        var s = if ("/" in n) n.substringBefore("/").trim() else n.trim()
        s = s.replace(Regex("""\s+"""), " ")
        return when {
            s.equals("UT", true) || s.equals("IN", true) -> ""
            s.startsWith("TEST", true) -> ""
            s.length <= 2 -> ""
            else -> s
        }
    }
}
