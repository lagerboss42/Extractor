package com.majk.extractor.data.postort

import android.content.Context
import com.majk.extractor.data.model.Shipment
import java.util.Locale

/**
 * Zip ↔ postort lookup from assets/postorter.csv.
 * One city per zip (sheet is cleaned). Used at manifest generation to correct
 * or flag city/zip mismatches. Never drops a row.
 */
object PostortLookup {

    private val zipToCity = mutableMapOf<String, String>()
    private val cityToZips = mutableMapOf<String, MutableSet<String>>()
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                context.assets.open("postorter.csv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.split(',')
                        if (parts.size < 2) return@forEach
                        val zip = parts[0].trim().filter { it.isDigit() }
                        val city = parts[1].trim()
                        if (zip.length == 5 && city.isNotBlank()) {
                            zipToCity[zip] = city
                            val key = fold(city)
                            cityToZips.getOrPut(key) { mutableSetOf() }.add(zip)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    /**
     * Apply city/zip rules.
     * - Zip known + city is a non-city (county/region) or blank → replace with canonical.
     * - Zip known + city does not match canonical → keep, mark city cell red.
     * - City known + zip not in its set → keep, mark zip cell red.
     * - Either side unknown → leave as-is.
     */
    fun apply(shipments: List<Shipment>): List<Shipment> {
        if (zipToCity.isEmpty()) return shipments
        return shipments.map { s ->
            val zip = s.zip.filter { it.isDigit() }.take(5)
            val canonical = zipToCity[zip]
            val cityFold = fold(s.city)
            val cityKnown = cityFold.isNotBlank() && cityToZips.containsKey(cityFold)

            var city = s.city
            var cityMismatch = false
            var zipMismatch = false

            when {
                canonical != null && (cityFold.isBlank() || isNonCity(cityFold)) -> {
                    city = canonical
                }
                canonical != null && cityFold.isNotBlank() && fold(canonical) != cityFold -> {
                    cityMismatch = true
                }
                cityKnown && zip.length == 5 && zip !in cityToZips[cityFold].orEmpty() -> {
                    zipMismatch = true
                }
            }

            if (city == s.city && !cityMismatch && !zipMismatch) s
            else s.copy(city = city, cityMismatch = cityMismatch, zipMismatch = zipMismatch)
        }
    }

    private fun isNonCity(folded: String): Boolean {
        if (folded.isBlank()) return true
        // County / region names only. Bare city names (Örebro, Kalmar) must not match.
        return folded.endsWith("lan") ||
            folded.endsWith("kommun") ||
            folded == "ostergotland" ||
            folded == "jonkoping" ||
            "ostergotlandslan" in folded ||
            "jonkopingslan" in folded ||
            "orebrolan" in folded ||
            "kalmarlan" in folded
    }

    private fun fold(raw: String): String {
        val nfd = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
        return nfd.lowercase(Locale.ROOT)
            .replace("å", "a").replace("ä", "a").replace("ö", "o").replace("é", "e")
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }
}
