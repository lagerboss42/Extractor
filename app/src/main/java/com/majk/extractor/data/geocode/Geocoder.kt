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
 *
 * Rows that already have lat/lon (from capture or cleaner) are not geocoded.
 */
object Geocoder {

    private const val TAG = "Geocoder"
    private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "Extractor/0.3.13a (private logistics tool)"

    @Volatile
    var lantmaterietUser: String? = null

    @Volatile
    var lantmaterietPass: String? = null

    private val cache = ConcurrentHashMap<String, Pair<Double, Double>>()

    suspend fun geocodeAll(shipments: List<Shipment>): List<Shipment> = withContext(Dispatchers.IO) {
        shipments.map { s ->
            // Do not call Nominatim when capture/cleaner already provided coordinates.
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
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Nominatim HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            val lat = obj.getString("lat").toDoubleOrNull() ?: return null
            val lon = obj.getString("lon").toDoubleOrNull() ?: return null
            lat to lon
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim: ${e.message}")
            null
        }
    }

    private fun lantmateriet(address: String, zip: String, city: String): Pair<Double, Double>? {
        return try {
            val user = lantmaterietUser ?: return null
            val pass = lantmaterietPass ?: return null
            val q = URLEncoder.encode("$address, $zip $city", "UTF-8")
            val url = URL(
                "https://api.lantmateriet.se/belagenhetsadress/v1/adresser?adress=$q&maxHits=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            val token = android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(),
                android.util.Base64.NO_WRAP
            )
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Basic $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Lantmäteriet HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val features = root.optJSONArray("features") ?: return null
            if (features.length() == 0) return null
            val geom = features.getJSONObject(0).optJSONObject("geometry") ?: return null
            val coords = geom.optJSONArray("coordinates") ?: return null
            if (coords.length() < 2) return null
            val lon = coords.getDouble(0)
            val lat = coords.getDouble(1)
            lat to lon
        } catch (e: Exception) {
            Log.w(TAG, "Lantmäteriet: ${e.message}")
            null
        }
    }
}
