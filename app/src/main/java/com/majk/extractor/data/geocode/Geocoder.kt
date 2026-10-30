package com.majk.extractor.data.geocode

import android.util.Log
import com.majk.extractor.data.model.Shipment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Geocodes Swedish addresses.
 *
 * Primary:  Nominatim (OpenStreetMap) – free, no key
 * Rate limit: ~1 request/second (Thread.sleep in nominatim())
 * In-memory cache avoids repeat lookups in the same session
 * Optional: Lantmäteriet Belägenhetsadress – set API credentials for higher accuracy
 */
object Geocoder {

    private const val TAG = "Geocoder"
    private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "Extractor/0.2 (private logistics tool)"

    @Volatile
    var lantmaterietUser: String? = null

    @Volatile
    var lantmaterietPass: String? = null

    private val cache = ConcurrentHashMap<String, Pair<Double, Double>>()

    suspend fun geocodeAll(shipments: List<Shipment>): List<Shipment> = withContext(Dispatchers.IO) {
        shipments.map { s ->
            if (s.lat != null && s.lon != null) return@map s
            val coords = lookup(s.address, s.zip, s.city)
            if (coords != null) s.copy(lat = coords.first, lon = coords.second) else s
        }
    }

    private fun lookup(address: String, zip: String, city: String): Pair<Double, Double>? {
        val key = "${address.trim().lowercase()}|${zip.trim()}|${city.trim().lowercase()}"
        cache[key]?.let { return it }

        val fromNominatim = nominatim(address, zip, city)
        if (fromNominatim != null) {
            cache[key] = fromNominatim
            return fromNominatim
        }

        if (!lantmaterietUser.isNullOrBlank() && !lantmaterietPass.isNullOrBlank()) {
            val fromLm = lantmateriet(address, zip, city)
            if (fromLm != null) {
                cache[key] = fromLm
                return fromLm
            }
        }

        return null
    }

    private fun nominatim(address: String, zip: String, city: String): Pair<Double, Double>? {
        return try {
            Thread.sleep(1100)

            val street = URLEncoder.encode(address, "UTF-8")
            val postal = URLEncoder.encode(zip, "UTF-8")
            val c = URLEncoder.encode(city, "UTF-8")
            val url = URL(
                "$NOMINATIM_URL?street=$street&postalcode=$postal&city=$c&country=Sweden&format=json&limit=1"
            )

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Nominatim HTTP $code")
                conn.disconnect()
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = JSONArray(body)
            if (arr.length() == 0) return null

            val obj = arr.getJSONObject(0)
            val lat = obj.getString("lat").toDoubleOrNull() ?: return null
            val lon = obj.getString("lon").toDoubleOrNull() ?: return null
            Log.d(TAG, "Nominatim: $address → $lat,$lon")
            Pair(lat, lon)
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim failed for $address: ${e.message}")
            null
        }
    }

    private fun lantmateriet(address: String, zip: String, city: String): Pair<Double, Double>? {
        val user = lantmaterietUser ?: return null
        val pass = lantmaterietPass ?: return null

        return try {
            val q = URLEncoder.encode("$address $zip $city", "UTF-8")
            val url = URL(
                "https://api.lantmateriet.se/distribution/produkter/belagenhetsadress/v4.2/referens/fritext?q=$q"
            )

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                val creds = android.util.Base64.encodeToString(
                    "$user:$pass".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                setRequestProperty("Authorization", "Basic $creds")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Lantmäteriet HTTP $code")
                conn.disconnect()
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            extractCoordsFromJson(body)
        } catch (e: Exception) {
            Log.w(TAG, "Lantmäteriet failed for $address: ${e.message}")
            null
        }
    }

    private fun extractCoordsFromJson(body: String): Pair<Double, Double>? {
        return try {
            if (!body.trimStart().startsWith("{")) return null
            val obj = JSONObject(body)
            if (obj.has("features")) {
                val features = obj.getJSONArray("features")
                if (features.length() > 0) {
                    val geom = features.getJSONObject(0).optJSONObject("geometry")
                    if (geom != null && geom.optString("type") == "Point") {
                        val coords = geom.getJSONArray("coordinates")
                        return Pair(coords.getDouble(1), coords.getDouble(0))
                    }
                }
            }
            if (obj.has("lat") && obj.has("lon")) {
                return Pair(obj.getDouble("lat"), obj.getDouble("lon"))
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() = cache.clear()
}
