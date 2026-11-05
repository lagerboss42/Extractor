package com.majk.extractor.data.db

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import java.io.File
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.majk.extractor.data.model.Shipment
import java.time.LocalDate

data class PkEntry(
    val address: String,
    val zip: String,
    val city: String,
    val code: String,
    val updatedAt: Long
)

data class VisitEntry(
    val address: String,
    val zip: String,
    val city: String,
    val shipments: Int,
    val totalWeight: Int,
    val date: String
)

/**
 * Persistent port codes and per-address visit log.
 * Same calendar day is overwritten (regenerating Excel does not double-count).
 *
 * Database path priority:
 *   1. /storage/emulated/0/extractor/extractor.db
 *   2. app-private external files dir
 *   3. internal databases dir
 */
object AddressStore {

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
        val h = helper ?: error("AddressStore not initialised")
        return h.writableDatabase
    }

    fun applyPortkods(shipments: List<Shipment>): List<Shipment> {
        val database = db()
        val now = System.currentTimeMillis()
        return shipments.map { s ->
            if (isKkb(s)) return@map s
            val found = PortkodExtractor.extract(s)
            if (found != null) {
                upsertCode(database, s, found, now)
                s.copy(portkod = found, portkodRemembered = false)
            } else {
                val saved = lookupCode(database, addressKey(s))
                if (saved != null) s.copy(portkod = saved, portkodRemembered = true) else s
            }
        }
    }

    fun recordVisits(shipments: List<Shipment>) {
        val database = db()
        val today = LocalDate.now().toString()
        val grouped = shipments.filter { !isKkb(it) }.groupBy { addressKey(it) }
        database.beginTransaction()
        try {
            for ((key, group) in grouped) {
                if (key.isBlank()) continue
                val first = group.first()
                val values = ContentValues().apply {
                    put("address_key", key)
                    put("address", first.address)
                    put("zip", first.zip)
                    put("city", first.city)
                    put("shipments", group.size)
                    put("total_weight", group.sumOf { it.weightKg })
                    put("visit_date", today)
                }
                database.insertWithOnConflict(
                    "address_log", null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        revision++
    }

    fun listPortkods(): List<PkEntry> {
        val database = db()
        val out = mutableListOf<PkEntry>()
        database.rawQuery(
            "SELECT address, zip, city, code, updated_at FROM pk_codes ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PkEntry(
                        address = c.getString(0),
                        zip = c.getString(1) ?: "",
                        city = c.getString(2) ?: "",
                        code = c.getString(3),
                        updatedAt = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    fun listVisits(): List<VisitEntry> {
        val database = db()
        val out = mutableListOf<VisitEntry>()
        database.rawQuery(
            "SELECT address, zip, city, shipments, total_weight, visit_date FROM address_log ORDER BY visit_date DESC, address",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VisitEntry(
                        address = c.getString(0),
                        zip = c.getString(1) ?: "",
                        city = c.getString(2) ?: "",
                        shipments = c.getInt(3),
                        totalWeight = c.getInt(4),
                        date = c.getString(5)
                    )
                )
            }
        }
        return out
    }

    fun updatePortkod(address: String, zip: String, code: String) {
        val database = db()
        val key = addressKey(address, zip)
        val n = database.update(
            "pk_codes",
            ContentValues().apply {
                put("code", code)
                put("updated_at", System.currentTimeMillis())
            },
            "address_key = ?",
            arrayOf(key)
        )
        if (n > 0) revision++
    }

    fun deletePortkod(address: String, zip: String) {
        val database = db()
        val key = addressKey(address, zip)
        database.beginTransaction()
        try {
            database.delete("pk_codes", "address_key = ?", arrayOf(key))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        revision++
    }

    fun updateVisit(address: String, zip: String, oldDate: String, shipments: Int, weight: Int, newDate: String) {
        val database = db()
        val key = addressKey(address, zip)
        database.beginTransaction()
        try {
            database.delete(
                "address_log",
                "address_key = ? AND visit_date = ?",
                arrayOf(key, oldDate)
            )
            val values = ContentValues().apply {
                put("address_key", key)
                put("address", address)
                put("zip", zip)
                put("city", "")
                put("shipments", shipments)
                put("total_weight", weight)
                put("visit_date", newDate)
            }
            database.insertWithOnConflict("address_log", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        revision++
    }

    fun deleteVisit(address: String, zip: String, date: String) {
        val database = db()
        val key = addressKey(address, zip)
        database.delete(
            "address_log",
            "address_key = ? AND visit_date = ?",
            arrayOf(key, date)
        )
        revision++
    }

    fun addressKey(s: Shipment): String = addressKey(s.address, s.zip)

    fun addressKey(address: String, zip: String): String {
        val a = address.lowercase()
            .replace("\u00e5", "a").replace("\u00e4", "a").replace("\u00f6", "o")
            .replace(Regex("[^a-z0-9]"), "")
        val z = zip.filter { it.isDigit() }
        if (a.length < 3) return ""
        return "\$a|\$z"
    }

    private fun isKkb(s: Shipment): Boolean =
        s.carrier.equals("kkb\u00f6j", ignoreCase = true)

    private class Helper(context: Context) : SQLiteOpenHelper(context, dbPath(context), null, 1) {
        companion object {
            private fun dbPath(context: Context): String {
                // Prefer external: /storage/emulated/0/extractor/extractor.db
                runCatching {
                    val dir = File(Environment.getExternalStorageDirectory(), "extractor")
                    if (!dir.exists()) dir.mkdirs()
                    if (dir.exists() && dir.canWrite()) {
                        return File(dir, "extractor.db").absolutePath
                    }
                }
                // Fallback to app-private external files dir
                runCatching {
                    val dir = context.getExternalFilesDir(null)
                    if (dir != null) {
                        val sub = File(dir, "extractor")
                        if (!sub.exists()) sub.mkdirs()
                        if (sub.exists() && sub.canWrite()) {
                            return File(sub, "extractor.db").absolutePath
                        }
                    }
                }
                // Last resort: internal databases dir
                return context.getDatabasePath("extractor.db").absolutePath
            }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE pk_codes (
                    address_key TEXT PRIMARY KEY,
                    address TEXT NOT NULL,
                    zip TEXT,
                    city TEXT,
                    code TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE address_log (
                    address_key TEXT NOT NULL,
                    address TEXT NOT NULL,
                    zip TEXT,
                    city TEXT,
                    shipments INTEGER NOT NULL,
                    total_weight INTEGER NOT NULL,
                    visit_date TEXT NOT NULL,
                    PRIMARY KEY (address_key, visit_date)
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun upsertCode(database: SQLiteDatabase, s: Shipment, code: String, now: Long) {
        val values = ContentValues().apply {
            put("address_key", addressKey(s))
            put("address", s.address)
            put("zip", s.zip)
            put("city", s.city)
            put("code", code)
            put("updated_at", now)
        }
        database.insertWithOnConflict("pk_codes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun lookupCode(database: SQLiteDatabase, key: String): String? {
        database.rawQuery(
            "SELECT code FROM pk_codes WHERE address_key = ?",
            arrayOf(key)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }
}

object PortkodExtractor {
    private val code = Regex("^[A-Za-z0-9*#]{4,10}$")
    private val labeled = Regex(
        """(?i)(?:pk|portkod|kod|code)\\s*[:#]?\\s*([A-Za-z0-9*#]{4,10})"""
    )

    fun extract(s: Shipment): String? {
        val hay = listOfNotNull(s.comment, s.portkod).joinToString(" ")
        labeled.find(hay)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }
}
