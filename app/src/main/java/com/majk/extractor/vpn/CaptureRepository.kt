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
            lastParseCount = parsedShipments.size
            persist()
        }
    }

    fun getShipments(): List<Shipment> = parsedShipments.toList()

    fun getRawLog(): String = rawChunks.joinToString("\n")

    /** Re-parse an entire log file into shipments (deduped). */
    fun loadFromLogText(text: String) {
        clear(persist = false)
        if (text.isBlank()) return
        rawChunks.add(text)
        addAll(OpterHttpParser.parse(text))
        lastParseCount = parsedShipments.size
        persist()
    }

    /**
     * Re-read opter_traffic_log.txt (ISO-8859-1, same as VPN capture) and rebuild stop list.
     * Use after a parser upgrade or when the UI shows 0 but the log has DEL lines.
     */
    fun reparseFromLogFile(logFile: File): Int {
        if (!logFile.exists() || logFile.length() == 0L) {
            clear(persist = true)
            return 0
        }
        return try {
            // Cap at 8 MiB to avoid OOM on pathological logs
            val maxBytes = 8L * 1024 * 1024
            val text = if (logFile.length() > maxBytes) {
                logFile.inputStream().use { ins ->
                    val buf = ByteArray(maxBytes.toInt())
                    val n = ins.read(buf)
                    String(buf, 0, n.coerceAtLeast(0), Charsets.ISO_8859_1)
                }
            } else {
                logFile.readText(Charsets.ISO_8859_1)
            }
            loadFromLogText(text)
            parsedShipments.size
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Re-parse OOM", e)
            lastParseCount = parsedShipments.size
            lastParseCount
        } catch (e: Exception) {
            Log.e(TAG, "Re-parse failed: ${e.message}", e)
            throw e
        }
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
        val addr = s.address.lowercase().replace(" ", "")
        val zip = s.zip.filter { it.isDigit() }
        val name = s.recipient.lowercase().replace(" ", "")
        val type = s.type.name
        return "$type|$name|$addr|$zip|${s.rawId.orEmpty()}"
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
                        put("carrier", s.carrier)
                        put("rawId", s.rawId ?: JSONObject.NULL)
                        if (s.lat != null) put("lat", s.lat) else put("lat", JSONObject.NULL)
                        if (s.lon != null) put("lon", s.lon) else put("lon", JSONObject.NULL)
                        put("sender", s.sender ?: JSONObject.NULL)
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
                        carrier = o.optString("carrier", ""),
                        rawId = o.optString("rawId", null)?.takeIf { it.isNotBlank() && it != "null" },
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
            lastParseCount = parsedShipments.size
            Log.i(TAG, "Restored $lastParseCount shipments from disk")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed: ${e.message}")
        }
    }
}
