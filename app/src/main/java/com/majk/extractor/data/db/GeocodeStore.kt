package com.majk.extractor.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

data class GeocodeEntry(
    val address: String,
    val zip: String,
    val city: String,
    val lat: Double?,
    val lon: Double?,
    val plusCode: String,
    val source: String,
    val found: Boolean,
    val lookedUp: Long
)

/**
 * Persistent geocode cache. Hits and misses.
 * A miss is reused for 30 days so a bad address is not asked again every manifest.
 * File lives under /storage/emulated/0/Extractor/geocode.db.
 */
object GeocodeStore {

    private const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000

    @Volatile
    var revision: Int = 0
        private set

    private var helper: Helper? = null

    fun init(context: Context) {
        if (helper == null) {
            helper = Helper(context.applicationContext)
        }
    }

    private fun db(): SQLiteDatabase {
        val h = helper ?: error("GeocodeStore not initialised")
        return h.writable()
    }

    fun key(address: String, zip: String, city: String): String {
        val a = address.trim().lowercase().replace(Regex("\\s+"), " ")
        val z = zip.filter { it.isDigit() }
        val c = city.trim().lowercase()
        return "$a|$z|$c"
    }

    /** A stored hit, or null. A fresh miss returns null and [isFreshMiss] is true. */
    fun lookup(address: String, zip: String, city: String): Lookup {
        val k = key(address, zip, city)
        db().rawQuery(
            "SELECT lat, lon, plus_code, source, found, looked_up FROM geocode WHERE address_key = ?",
            arrayOf(k)
        ).use { c ->
            if (!c.moveToFirst()) return Lookup.None
            val found = c.getInt(4) == 1
            val lookedUp = c.getLong(5)
            if (!found) {
                val fresh = System.currentTimeMillis() - lookedUp < MISS_TTL_MS
                return if (fresh) Lookup.FreshMiss else Lookup.None
            }
            val lat = if (c.isNull(0)) null else c.getDouble(0)
            val lon = if (c.isNull(1)) null else c.getDouble(1)
            if (lat == null || lon == null) return Lookup.None
            return Lookup.Hit(lat, lon, c.getString(2).orEmpty(), c.getString(3).orEmpty())
        }
    }

    fun save(
        address: String,
        zip: String,
        city: String,
        lat: Double?,
        lon: Double?,
        plusCode: String,
        source: String,
        found: Boolean
    ) {
        val values = ContentValues().apply {
            put("address_key", key(address, zip, city))
            put("address", address.trim())
            put("zip", zip.filter { it.isDigit() })
            put("city", city.trim())
            if (lat != null) put("lat", lat) else putNull("lat")
            if (lon != null) put("lon", lon) else putNull("lon")
            put("plus_code", plusCode)
            put("source", source)
            put("found", if (found) 1 else 0)
            put("looked_up", System.currentTimeMillis())
        }
        db().insertWithOnConflict("geocode", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun search(query: String): List<GeocodeEntry> {
        val q = "%${query.trim()}%"
        val sql = """
            SELECT address, zip, city, lat, lon, plus_code, source, found, looked_up
            FROM geocode
            WHERE address LIKE ? OR zip LIKE ? OR city LIKE ? OR plus_code LIKE ?
            ORDER BY looked_up DESC
        """.trimIndent()
        val out = mutableListOf<GeocodeEntry>()
        db().rawQuery(sql, arrayOf(q, q, q, q)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    GeocodeEntry(
                        address = c.getString(0).orEmpty(),
                        zip = c.getString(1).orEmpty(),
                        city = c.getString(2).orEmpty(),
                        lat = if (c.isNull(3)) null else c.getDouble(3),
                        lon = if (c.isNull(4)) null else c.getDouble(4),
                        plusCode = c.getString(5).orEmpty(),
                        source = c.getString(6).orEmpty(),
                        found = c.getInt(7) == 1,
                        lookedUp = c.getLong(8)
                    )
                )
            }
        }
        return out
    }

    fun delete(address: String, zip: String, city: String): Boolean {
        val n = db().delete("geocode", "address_key = ?", arrayOf(key(address, zip, city)))
        if (n > 0) bump()
        return n > 0
    }

    fun clear(): Int {
        val n = db().delete("geocode", null, null)
        bump()
        return n
    }

    private fun bump() {
        revision++
    }

    sealed class Lookup {
        data class Hit(val lat: Double, val lon: Double, val plusCode: String, val source: String) : Lookup()
        object FreshMiss : Lookup()
        object None : Lookup()
    }

    private class Helper(context: Context) {
        private val dir = java.io.File("/storage/emulated/0/Extractor")
        private val file = java.io.File(dir, "geocode.db")
        private val internal = context.getDatabasePath("geocode.db")
        private var database: SQLiteDatabase? = null

        @Synchronized
        fun writable(): SQLiteDatabase {
            database?.takeIf { it.isOpen }?.let { return it }
            val opened = openExternal() ?: openInternal()
            ensureSchema(opened)
            database = opened
            return opened
        }

        private fun openExternal(): SQLiteDatabase? = try {
            if (!dir.exists() && !dir.mkdirs()) null
            else SQLiteDatabase.openOrCreateDatabase(file, null)
        } catch (_: Exception) {
            null
        }

        private fun openInternal(): SQLiteDatabase {
            internal.parentFile?.mkdirs()
            return SQLiteDatabase.openOrCreateDatabase(internal, null)
        }

        private fun ensureSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS geocode (
                    address_key TEXT PRIMARY KEY,
                    address TEXT NOT NULL,
                    zip TEXT,
                    city TEXT,
                    lat REAL,
                    lon REAL,
                    plus_code TEXT,
                    source TEXT,
                    found INTEGER NOT NULL,
                    looked_up INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}
