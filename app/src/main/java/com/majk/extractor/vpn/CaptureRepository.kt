package com.majk.extractor.vpn

import android.content.Context
import android.util.Log
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.data.parser.OpterHttpParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds parsed shipments for the current capture session.
 * Deduplicates aggressively (re-parsed packets must not inflate the count).
 * Persists to disk so a crash during Excel export does not wipe the capture.
 */
object CaptureRepository {

    private const val TAG = "CaptureRepository"
    private const val FILE_NAME = "capture_shipments.json"

    private val rawChunks = CopyOnWriteArrayList<String>()
    private val parsedShipments = CopyOnWriteArrayList<Shipment>()

    @Volatile
    private var persistFile: File? = null

    @Volatile
    var lastParseCount: Int = 0
        private set

    fun init(context: Context) {
        persistFile = File(context.getExternalFilesDir(null), FILE_NAME)
        if (parsedShipments.isEmpty()) {
            restore()
        }
    }

    fun appendPayload(chunk: String) {
        if (chunk.isBlank()) return
        rawChunks.add(chunk)
        addAll(OpterHttpParser.parse(chunk))
    }

    fun addAll(found: List<Shipment>) {
        if (found.isEmpty()) return
        var added = 0
        for (s in found) {
            val key = dedupeKey(s)
            val exists = parsedShipments.any { dedupeKey(it) == key }
            if (!exists) {
                parsedShipments.add(s)
                added++
            }
        }
        if (added > 0) {
            lastParseCount = shipmentCount()
            persist()
        }
    }

    /**
     * In-app stop count: one complete shipment = one stop.
     * Packages of the same shipment (same shipment ID) count as one.
     * Rows with no shipment ID fall back to package ID, then to a plain row.
     */
    fun shipmentCount(): Int {
        val ids = mutableSetOf<String>()
        var bare = 0
        for (s in parsedShipments) {
            val id = s.shipmentId?.takeIf { it.isNotBlank() }
                ?: s.rawId?.takeIf { it.isNotBlank() }
            if (id != null) ids.add(id) else bare++
        }
        return ids.size + bare
    }

    fun getShipments(): List<Shipment> = parsedShipments.toList()

    fun getRawLog(): String = rawChunks.joinToString("\n")

    /** Re-parse an entire log file into shipments (deduped). */
    fun loadFromLogText(text: String) {
        clear(persist = false)
        if (text.isBlank()) return
        rawChunks.add(text)
        addAll(OpterHttpParser.parse(text))
        lastParseCount = shipmentCount()
        persist()
    }

    /**
     * Re-read opter_traffic_log.txt (ISO-8859-1, same as VPN capture) and rebuild stop list.
     * Use after a parser upgrade or when the UI shows 0 but the log has DEL lines.
     */
    fun reparseFromLogFile(logFile: java.io.File): Int {
        if (!logFile.exists() || logFile.length() == 0L) {
            clear(persist = true)
            return 0
        }
        val text = logFile.readText(Charsets.ISO_8859_1)
        loadFromLogText(text)
        return shipmentCount()
    }

    /**
     * Load a capture file and merge into the existing stop list.
     * Same package id is not added twice. Returns (added, total), or (-1, current) on error.
     */
    fun loadCaptureFileAdditive(file: java.io.File): Pair<Int, Int> {
        if (!file.exists() || file.length() == 0L) return 0 to shipmentCount()
        val before = shipmentCount()
        return try {
            val looksPcap = file.extension.lowercase() in listOf("pcap", "pcapng") || isPcapMagic(file)
            val text = if (looksPcap) {
                val streams = PcapReader.extractTcpStreams(file)
                if (streams.isEmpty()) return 0 to shipmentCount()
                streams.joinToString("\n") { it.payload.toString(Charsets.ISO_8859_1) }
            } else {
                file.readText(Charsets.ISO_8859_1)
            }
            if (text.isNotBlank()) {
                rawChunks.add(text)
                addAll(OpterHttpParser.parse(text))
            }
            val after = shipmentCount()
            val added = (after - before).coerceAtLeast(0)
            added to after
        } catch (e: Exception) {
            Log.w(TAG, "loadCaptureFileAdditive: ${e.message}")
            -1 to shipmentCount()
        }
    }

    /**
     * Load a capture file the user picked manually.
     * .pcap / .pcapng → extract TCP streams, decode ISO-8859-1, parse.
     * .txt / .log / other → treat as ISO-8859-1 traffic log.
     * Returns shipment count, or -1 on error.
     * Replaces the current list. Prefer loadCaptureFileAdditive for multi-file loads.
     */
    fun loadCaptureFile(file: java.io.File): Int {
        if (!file.exists() || file.length() == 0L) return 0
        return try {
            val looksPcap = file.extension.lowercase() in listOf("pcap", "pcapng") || isPcapMagic(file)
            if (looksPcap) {
                val streams = PcapReader.extractTcpStreams(file)
                if (streams.isEmpty()) return 0
                val combined = streams.joinToString("\n") {
                    it.payload.toString(Charsets.ISO_8859_1)
                }
                loadFromLogText(combined)
                shipmentCount()
            } else {
                loadFromLogText(file.readText(Charsets.ISO_8859_1))
                shipmentCount()
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadCaptureFile: ${e.message}")
            -1
        }
    }

    private fun isPcapMagic(file: java.io.File): Boolean {
        if (file.length() < 4) return false
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) }
        val magic = ((head[0].toInt() and 0xFF) shl 24) or
            ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or
            (head[3].toInt() and 0xFF)
        return magic == 0xA1B2C3D4.toInt() || magic == 0xD4C3B2A1.toInt() ||
            magic == 0xA1B23C4D.toInt() || magic == 0x4D3CB2A1.toInt() ||
            magic == 0x0A0D0D0A // PCAPNG
    }

    fun clear(persist: Boolean = true) {
        rawChunks.clear()
        parsedShipments.clear()
        lastParseCount = 0
        if (persist) {
            try {
                persistFile?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun dedupeKey(s: Shipment): String {
        if (!s.rawId.isNullOrBlank()) return "id:${s.rawId}"
        return listOf(
            s.type.name,
            s.address.lowercase().replace(" ", ""),
            s.zip.filter { it.isDigit() },
            s.recipient.lowercase().substringBefore("/").trim(),
            s.colliCount.toString(),
            s.weightKg.toString(),
            s.carrier.lowercase()
        ).joinToString("|")
    }

    private fun persist() {
        val file = persistFile ?: return
        try {
            val arr = JSONArray()
            for (s in parsedShipments) {
                arr.put(
                    JSONObject().apply {
                        put("type", s.type.name)
                        put("recipient", s.recipient)
                        put("address", s.address)
                        put("zip", s.zip)
                        put("city", s.city)
                        put("colliCount", s.colliCount)
                        put("weightKg", s.weightKg)
                        put("portkod", s.portkod ?: JSONObject.NULL)
                        put("comment", s.comment ?: JSONObject.NULL)
                        put("lgh", s.lgh ?: JSONObject.NULL)
                        put("carrier", s.carrier)
                        put("rawId", s.rawId ?: JSONObject.NULL)
                        put("shipmentId", s.shipmentId ?: JSONObject.NULL)
                        put("sender", s.sender ?: JSONObject.NULL)
                        if (s.lat != null) put("lat", s.lat) else put("lat", JSONObject.NULL)
                        if (s.lon != null) put("lon", s.lon) else put("lon", JSONObject.NULL)
                        if (s.earliestMinutes != null) put("earliestMinutes", s.earliestMinutes)
                            else put("earliestMinutes", JSONObject.NULL)
                        if (s.latestMinutes != null) put("latestMinutes", s.latestMinutes)
                            else put("latestMinutes", JSONObject.NULL)
                    }
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val file = persistFile ?: return
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Shipment>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Shipment(
                        type = runCatching { ShipmentType.valueOf(o.getString("type")) }
                            .getOrDefault(ShipmentType.DEL),
                        recipient = o.optString("recipient", ""),
                        address = o.optString("address", ""),
                        zip = o.optString("zip", ""),
                        city = o.optString("city", ""),
                        colliCount = o.optInt("colliCount", 0),
                        weightKg = o.optInt("weightKg", 0),
                        portkod = o.optString("portkod", null)?.takeIf { it.isNotBlank() && it != "null" },
                        comment = o.optString("comment", null)?.takeIf { it.isNotBlank() && it != "null" },
                        lgh = o.optString("lgh", null)?.takeIf { it.isNotBlank() && it != "null" },
                        carrier = o.optString("carrier", ""),
                        rawId = o.optString("rawId", null)?.takeIf { it.isNotBlank() && it != "null" },
                        shipmentId = o.optString("shipmentId", null)?.takeIf { it.isNotBlank() && it != "null" },
                        lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                        lon = if (o.isNull("lon")) null else o.optDouble("lon"),
                        sender = o.optString("sender", null)?.takeIf { it.isNotBlank() && it != "null" },
                        earliestMinutes = if (o.isNull("earliestMinutes")) null
                            else o.optInt("earliestMinutes"),
                        latestMinutes = if (o.isNull("latestMinutes")) null
                            else o.optInt("latestMinutes")
                    )
                )
            }
            parsedShipments.clear()
            parsedShipments.addAll(list)
            lastParseCount = shipmentCount()
            Log.i(TAG, "Restored $lastParseCount shipments from disk")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed: ${e.message}")
        }
    }
}
