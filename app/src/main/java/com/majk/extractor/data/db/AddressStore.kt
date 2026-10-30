package com.majk.extractor.data.db

import android.content.ContentValues
import android.content.Context
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
            if (isKkb(s) || addressKey(s).isBlank()) return@map s

            val (found, cleanedComment) = PortkodExtractor.extract(s.portkod, s.comment)
            if (found != null) {
                upsertCode(database, s, found, now)
                s.copy(portkod = found, comment = cleanedComment, portkodRemembered = false)
            } else {
                val saved = lookupCode(database, addressKey(s))
                if (saved != null) {
                    s.copy(portkod = saved, portkodRemembered = true)
                } else {
                    s
                }
            }
        }.also { bump() }
    }

    fun recordVisits(shipments: List<Shipment>) {
        val database = db()
        val today = LocalDate.now().toString()
        val groups = shipments
            .filter { !isKkb(it) && addressKey(it).isNotBlank() }
            .groupBy { addressKey(it) }

        database.beginTransaction()
        try {
            for ((key, list) in groups) {
                val first = list.first()
                val values = ContentValues().apply {
                    put("address_key", key)
                    put("address", first.address)
                    put("zip", first.zip)
                    put("city", first.city)
                    put("shipments", list.size)
                    put("total_weight", list.sumOf { it.weightKg })
                    put("visit_date", today)
                }
                database.insertWithOnConflict(
                    "address_log",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        bump()
    }

    fun searchCodes(query: String): List<PkEntry> {
        val q = "%${query.trim()}%"
        val sql = """
            SELECT address, zip, city, code, updated_at
            FROM pk_codes
            WHERE address LIKE ? OR zip LIKE ? OR city LIKE ? OR code LIKE ?
            ORDER BY address COLLATE NOCASE
        """.trimIndent()
        val out = mutableListOf<PkEntry>()
        db().rawQuery(sql, arrayOf(q, q, q, q)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PkEntry(
                        address = c.getString(0),
                        zip = c.getString(1).orEmpty(),
                        city = c.getString(2).orEmpty(),
                        code = c.getString(3),
                        updatedAt = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    fun searchVisits(query: String): List<VisitEntry> {
        val q = "%${query.trim()}%"
        val sql = """
            SELECT address, zip, city, shipments, total_weight, visit_date
            FROM address_log
            WHERE address LIKE ? OR zip LIKE ? OR city LIKE ?
            ORDER BY visit_date DESC, address COLLATE NOCASE
        """.trimIndent()
        val out = mutableListOf<VisitEntry>()
        db().rawQuery(sql, arrayOf(q, q, q)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VisitEntry(
                        address = c.getString(0),
                        zip = c.getString(1).orEmpty(),
                        city = c.getString(2).orEmpty(),
                        shipments = c.getInt(3),
                        totalWeight = c.getInt(4),
                        date = c.getString(5)
                    )
                )
            }
        }
        return out
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
            "SELECT code FROM pk_codes WHERE address_key = ? LIMIT 1",
            arrayOf(key)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun bump() {
        revision++
    }

    fun addressKey(s: Shipment): String = addressKey(s.address, s.zip)

    fun addressKey(address: String, zip: String): String {
        val a = address.lowercase()
            .replace("\u00e5", "a").replace("\u00e4", "a").replace("\u00f6", "o")
            .replace(Regex("[^a-z0-9]"), "")
        val z = zip.filter { it.isDigit() }
        if (a.length < 3) return ""
        return "$a|$z"
    }

    private fun isKkb(s: Shipment): Boolean =
        s.carrier.equals("kkb\u00f6j", ignoreCase = true)

    private class Helper(context: Context) : SQLiteOpenHelper(context, "extractor.db", null, 1) {
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
}

object PortkodExtractor {
    private val code = Regex("^[A-Za-z0-9*#]{4,10}$")
    private val labeled = Regex(
        "(?i)(?:\\bPK\\b|\\bportkod\\b|\\bkod\\b)\\s*[:.=-]?\\s*([A-Za-z0-9*#]{4,10})"
    )

    fun extract(portkod: String?, comment: String?): Pair<String?, String?> {
        normalizeToken(portkod)?.let { return it to comment }
        if (comment.isNullOrBlank()) return null to comment
        val match = labeled.find(comment) ?: return null to comment
        val found = match.groupValues[1]
        if (!code.matches(found)) return null to comment
        val cleaned = comment.removeRange(match.range)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('/', ',', ';', ' ')
            .ifBlank { null }
        return found to cleaned
    }

    private fun normalizeToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val direct = raw.trim()
        if (code.matches(direct)) return direct
        val labeledHit = labeled.find(direct)?.groupValues?.getOrNull(1)
        if (labeledHit != null && code.matches(labeledHit)) return labeledHit
        val stripped = direct
            .replace(Regex("(?i)^PK\\s*[:.=-]?\\s*"), "")
            .trim()
        return stripped.takeIf { code.matches(it) }
    }
}
