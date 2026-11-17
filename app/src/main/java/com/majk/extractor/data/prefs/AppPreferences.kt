package com.majk.extractor.data.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * User-adjustable settings.
 *
 * Non-secret settings live in /storage/emulated/0/Extractor/settings.json
 * so they survive uninstall (same folder as the databases).
 * Lantmäteriet credentials stay in SharedPreferences (never on shared storage).
 */
object AppPreferences {

    private const val PREFS = "extractor_prefs"
    private const val KEY_LM_USER = "lm_user"
    private const val KEY_LM_PASS = "lm_pass"

    private const val FILE_NAME = "settings.json"
    private val dir = File("/storage/emulated/0/Extractor")
    private val file = File(dir, FILE_NAME)

    private var prefs: SharedPreferences? = null
    private var cached: JSONObject? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        if (cached == null) {
            cached = loadOrMigrate()
        }
    }

    private fun p(): SharedPreferences = prefs ?: error("AppPreferences not initialised")

    private fun loadOrMigrate(): JSONObject {
        if (file.exists()) {
            return try {
                JSONObject(file.readText())
            } catch (_: Exception) {
                JSONObject()
            }
        }
        // One-time migration from old SharedPreferences.
        val old = p()
        val migrated = JSONObject().apply {
            put("quiet_minutes", old.getInt("quiet_minutes", 20))
            put("geocode_enabled", old.getBoolean("geocode_enabled", false))
            put("prune_days", old.getInt("prune_days", 31))
            val sections = old.getStringSet("visible_kkb_sections", null)
            if (sections != null) {
                put("visible_kkb_sections", sections.joinToString(","))
            }
        }
        persist(migrated)
        return migrated
    }

    private fun persist(obj: JSONObject) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(obj.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            cached = obj
        } catch (_: Exception) {
            cached = obj
        }
    }

    private fun current(): JSONObject = cached ?: JSONObject()

    private fun update(block: JSONObject.() -> Unit) {
        val obj = current()
        obj.block()
        persist(obj)
    }

    var quietMinutes: Int
        get() = current().optInt("quiet_minutes", 20).coerceIn(1, 60)
        set(v) = update { put("quiet_minutes", v.coerceIn(1, 60)) }

    var geocodeEnabled: Boolean
        get() = current().optBoolean("geocode_enabled", false)
        set(v) = update { put("geocode_enabled", v) }

    /** "nominatim" or "lantmateriet". One provider at a time. */
    var geocodeProvider: String
        get() = current().optString("geocode_provider", "nominatim")
        set(v) = update { put("geocode_provider", if (v == "lantmateriet") v else "nominatim") }

    fun useLantmateriet(): Boolean = geocodeProvider == "lantmateriet"

    /**
     * Zip allowlist for geocoding.
     * Empty string → geocode all.
     * Any token that is not exactly five digits → malformed → geocode nothing.
     * Otherwise only those zips are geocoded.
     */
    var geocodeZips: String
        get() = current().optString("geocode_zips", "")
        set(v) = update { put("geocode_zips", v) }

    /** Result of parsing the allowlist. */
    sealed class ZipFilter {
        /** List empty → geocode everything. */
        object All : ZipFilter()
        /** List has a non-five-digit token → geocode nothing. */
        object None : ZipFilter()
        /** Well-formed non-empty list. */
        data class Only(val zips: Set<String>) : ZipFilter()
    }

    fun geocodeZipFilter(): ZipFilter {
        val raw = geocodeZips.trim()
        if (raw.isBlank()) return ZipFilter.All
        // Collapse "585 71" into "58571" before splitting, so Swedish spacing is valid.
        val normalized = raw.replace(Regex("(\\d)\\s+(?=\\d)"), "$1")
        val tokens = normalized.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ZipFilter.All
        val bad = tokens.any { it.length != 5 || !it.all { c -> c.isDigit() } }
        if (bad) return ZipFilter.None
        return ZipFilter.Only(tokens.toSet())
    }

    /** Cleaned display form: sorted unique five-digit zips, or the raw text if malformed. */
    fun geocodeZipsCleaned(): String {
        return when (val f = geocodeZipFilter()) {
            is ZipFilter.Only -> f.zips.sorted().joinToString(", ")
            else -> geocodeZips
        }
    }

    /**
     * Words that must not have OE/AE/AO rewritten to Ö/Ä/Å.
     * Newline-separated. Match is a substring and case-insensitive,
     * so "Cloetta" also protects "Cloettavägen".
     */
    var oeExclusions: String
        get() = current().optString("oe_exclusions", "")
        set(v) = update { put("oe_exclusions", v) }

    fun oeExclusionWords(): List<String> =
        oeExclusions.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    var lantmaterietUser: String
        get() = p().getString(KEY_LM_USER, "") ?: ""
        set(v) = p().edit().putString(KEY_LM_USER, v).apply()

    var lantmaterietPass: String
        get() = p().getString(KEY_LM_PASS, "") ?: ""
        set(v) = p().edit().putString(KEY_LM_PASS, v).apply()

    var pruneDays: Int
        get() = current().optInt("prune_days", 31).coerceIn(1, 365)
        set(v) = update { put("prune_days", v.coerceIn(1, 365)) }

    fun visibleSections(): Set<String> {
        val raw = current().optString("visible_kkb_sections", "")
        if (raw.isBlank()) {
            return setOf("Malmslätt", "Ljungsbro", "Vreta Kloster", "Skänninge")
        }
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setVisibleSections(sections: Set<String>) {
        update { put("visible_kkb_sections", sections.joinToString(",")) }
    }
}
