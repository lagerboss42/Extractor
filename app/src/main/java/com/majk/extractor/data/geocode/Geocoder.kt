package com.majk.extractor.data.geocode

import android.util.Log
import com.majk.extractor.BuildConfig
import com.majk.extractor.data.db.GeocodeStore
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.prefs.AppPreferences
import com.majk.extractor.util.PlusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Geocodes Swedish addresses.
 *
 * One provider at a time, chosen in Settings: Nominatim or Lantmäteriet.
 * Order: capture coordinates, then the geocode database, then the chosen provider.
 * Hits and misses are stored. A miss is reused for 30 days.
 */
object Geocoder {

    private const val TAG = "Geocoder"
    private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"

    @Volatile
    var lantmaterietUser: String? = null

    @Volatile
    var lantmaterietPass: String? = null

    private fun userAgent(): String =
        "Extractor/${BuildConfig.VERSION_NAME} (single-user, non-profit)"

    suspend fun geocodeAll(
        shipments: List<Shipment>,
        allowedZips: Set<String>? = null
    ): List<Shipment> = withContext(Dispatchers.IO) {
        val useLm = AppPreferences.useLantmateriet()
        shipments.map { s ->
            if (s.lat != null && s.lon != null) return@map s
            if (allowedZips != null) {
                val zip = s.zip.filter { it.isDigit() }
                if (zip !in allowedZips) return@map s
            }
            val coords = lookup(s.address, s.zip, s.city, useLm)
            if (coords != null) s.copy(lat = coords.first, lon = coords.second) else s
        }
    }

    private fun lookup(address: String, zip: String, city: String, useLm: Boolean): Pair<Double, Double>? {
        when (val cached = GeocodeStore.lookup(address, zip, city)) {
            is GeocodeStore.Lookup.Hit -> return cached.lat to cached.lon
            is GeocodeStore.Lookup.FreshMiss -> return null
            is GeocodeStore.Lookup.None -> Unit
        }

        val coords = if (useLm) lantmateriet(address, zip, city) else nominatim(address, zip, city)
        val source = if (useLm) "lantmateriet" else "nominatim"
        if (coords != null) {
            val plus = PlusCode.short(coords.first, coords.second, city)
            GeocodeStore.save(address, zip, city, coords.first, coords.second, plus, source, true)
            return coords
        }
        // Only a definitive empty result is a miss. A network failure is not cached.
        if (lastWasEmpty) {
            GeocodeStore.save(address, zip, city, null, null, "", source, false)
        }
        return null
    }

    @Volatile
    private var lastWasEmpty = false

    private fun nominatim(address: String, zip: String, city: String): Pair<Double, Double>? {
        lastWasEmpty = false
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
                setRequestProperty("User-Agent", userAgent())
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
            if (arr.length() == 0) {
                lastWasEmpty = true
                return null
            }

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
        lastWasEmpty = false
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
            val coords = extractCoordsFromJson(body)
            if (coords == null) lastWasEmpty = true
            coords
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
}
