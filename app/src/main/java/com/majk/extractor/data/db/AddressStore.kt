package com.majk.extractor.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.majk.extractor.data.model.Shipment
import java.time.LocalDate

data class PkEntry(
    val address: String,
    val zip: String,
    val city: String,
    val code: String,
    val recipient: String?,
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
 * Separate SQLite files for entry codes and visits.
 */
object AddressStore {

    @Volatile
    var revision: Int = 0
        private set

    private var codesHelper: Helper? = null
    private var visitsHelper: Helper? = null

    fun init(context: Context) {
        if (codesHelper == null) {
            codesHelper = Helper(context.applicationContext, "entry_codes.db", true)
        }
        if (visitsHelper == null) {
            visitsHelper = Helper(context.applicationContext, "visits.db", false)
        }
    }

    private fun codesDb(): SQLiteDatabase {
        val h = codesHelper ?: error("AddressStore codes not initialised")
        return h.writable()
    }

    private fun visitsDb(): SQLiteDatabase {
        val h = visitsHelper ?: error("AddressStore visits not initialised")
        return h.writable()
    }

    fun applyPortkods(shipments: List<Shipment>): List<Shipment> {
        val database = codesDb()
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
        val database = visitsDb()
        val today = LocalDate.now().toString()
        val groups = shipments
            .filter { !isKkb(it) && visitKey(it).isNotBlank() }
            .groupBy { visitKey(it) }

        database.beginTransaction()
        try {
            for ((key, list) in groups) {
                val label = visitLabel(list)
                val values = ContentValues().apply {
                    put("address_key", key)
                    put("address", label.address)
                    put("zip", label.zip)
                    put("city", label.city)
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

    private fun visitKey(s: Shipment): String {
        val rec = s.recipient.trim()
        val port = when {
            rec.equals("Port K", true) -> "port:k"
            rec.equals("Port D", true) -> "port:d"
            rec.equals("Port F", true) -> "port:f"
            rec.equals("Cloetta", true) || rec.equals("Cloetta Godsmottagning", true) ->
                "cloetta:${rec.lowercase()}"
            else -> null
        }
        if (port != null) return "$port|${s.type.name}"
        if (s.carrier.startsWith("Region", ignoreCase = true)) {
            val ak = addressKey(s)
            return if (ak.isNotBlank()) "addr:$ak" else ""
        }
        val name = rec.lowercase()
            .replace("å", "a").replace("ä", "a").replace("ö", "o")
            .replace(Regex("[^a-z0-9]"), "")
        return if (name.length >= 2) "recip:$name" else ""
    }

    private data class VisitLabel(val address: String, val zip: String, val city: String)

    private fun visitLabel(list: List<Shipment>): VisitLabel {
        val first = list.first()
        val rec = first.recipient.trim()
        val isRegion = first.carrier.startsWith("Region", ignoreCase = true)
        val address = when {
            rec.equals("Port K", true) || rec.equals("Port D", true) || rec.equals("Port F", true) -> rec
            rec.equals("Cloetta", true) || rec.equals("Cloetta Godsmottagning", true) -> rec
            isRegion -> first.address
            else -> rec.ifBlank { first.address }
        }
        return VisitLabel(address, first.zip, first.city)
    }

    fun searchCodes(query: String): List<PkEntry> {
        val q = "%${query.trim()}%"
        val sql = """
            SELECT address, zip, city, code, recipient, updated_at
            FROM pk_codes
            WHERE address LIKE ? OR zip LIKE ? OR city LIKE ? OR code LIKE ? OR recipient LIKE ?
            ORDER BY COALESCE(recipient, address) COLLATE NOCASE
        """.trimIndent()
        val out = mutableListOf<PkEntry>()
        codesDb().rawQuery(sql, arrayOf(q, q, q, q, q)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PkEntry(
                        address = c.getString(0),
                        zip = c.getString(1).orEmpty(),
                        city = c.getString(2).orEmpty(),
                        code = c.getString(3),
                        recipient = c.getString(4)?.takeIf { it.isNotBlank() },
                        updatedAt = c.getLong(5)
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
        visitsDb().rawQuery(sql, arrayOf(q, q, q)).use { c ->
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

    fun updateCode(address: String, zip: String, newCode: String): Boolean {
        val key = addressKey(address, zip)
        if (key.isBlank()) return false
        val code = newCode.trim()
        if (code.isEmpty()) return false
        val values = ContentValues().apply {
            put("code", code)
            put("updated_at", System.currentTimeMillis())
        }
        val n = codesDb().update("pk_codes", values, "address_key = ?", arrayOf(key))
        if (n > 0) bump()
        return n > 0
    }

    fun deleteCode(address: String, zip: String): Boolean {
        val key = addressKey(address, zip)
        if (key.isBlank()) return false
        val n = codesDb().delete("pk_codes", "address_key = ?", arrayOf(key))
        if (n > 0) bump()
        return n > 0
    }

    fun clearCodes(): Int {
        val n = codesDb().delete("pk_codes", null, null)
        if (n > 0) bump()
        return n
    }

    fun clearVisits(): Int {
        val n = visitsDb().delete("address_log", null, null)
        if (n > 0) bump()
        return n
    }

    fun updateVisit(
        address: String,
        zip: String,
        oldDate: String,
        shipments: Int,
        totalWeight: Int,
        newDate: String
    ): Boolean {
        val key = visitKeyForStored(address, zip, oldDate) ?: return false
        val date = newDate.trim()
        if (date.isEmpty() || shipments < 0 || totalWeight < 0) return false
        val database = visitsDb()
        if (date == oldDate) {
            val values = ContentValues().apply {
                put("shipments", shipments)
                put("total_weight", totalWeight)
            }
            val n = database.update(
                "address_log",
                values,
                "address_key = ? AND visit_date = ?",
                arrayOf(key, oldDate)
            )
            if (n > 0) bump()
            return n > 0
        }
        database.beginTransaction()
        try {
            val cur = database.rawQuery(
                "SELECT address, zip, city FROM address_log WHERE address_key = ? AND visit_date = ? LIMIT 1",
                arrayOf(key, oldDate)
            )
            val (addr, z, city) = cur.use { c ->
                if (!c.moveToFirst()) return false
                Triple(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
            }
            database.delete(
                "address_log",
                "address_key = ? AND visit_date = ?",
                arrayOf(key, oldDate)
            )
            val values = ContentValues().apply {
                put("address_key", key)
                put("address", addr)
                put("zip", z)
                put("city", city)
                put("shipments", shipments)
                put("total_weight", totalWeight)
                put("visit_date", date)
            }
            database.insertWithOnConflict(
                "address_log",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        bump()
        return true
    }

    fun deleteVisit(address: String, zip: String, date: String): Boolean {
        val key = visitKeyForStored(address, zip, date) ?: return false
        val n = visitsDb().delete(
            "address_log",
            "address_key = ? AND visit_date = ?",
            arrayOf(key, date)
        )
        if (n > 0) bump()
        return n > 0
    }

    private fun visitKeyForStored(address: String, zip: String, date: String): String? {
        visitsDb().rawQuery(
            "SELECT address_key FROM address_log WHERE address = ? AND zip = ? AND visit_date = ? LIMIT 1",
            arrayOf(address, zip, date)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun upsertCode(database: SQLiteDatabase, s: Shipment, code: String, now: Long) {
        val values = ContentValues().apply {
            put("address_key", addressKey(s))
            put("address", s.address)
            put("zip", s.zip)
            put("city", s.city)
            put("code", code)
            put("recipient", s.recipient.takeIf { it.isNotBlank() })
            put("updated_at", now)
        }
        database.insertWithOnConflict("pk_codes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun lookupCode(database: SQLiteDatabase, key: String): String? {
        codesDb().rawQuery(
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

    fun insertManualCode(
        code: String,
        recipient: String?,
        address: String,
        zip: String,
        city: String = ""
    ): Boolean {
        val c = code.trim()
        val a = address.trim()
        val z = zip.trim()
        if (c.isEmpty() || a.isEmpty() || z.isEmpty()) return false
        if (!c.matches(Regex("^[A-Za-z0-9*#]+$"))) return false
        val key = addressKey(a, z)
        if (key.isBlank()) return false
        val values = ContentValues().apply {
            put("address_key", key)
            put("address", a)
            put("zip", z)
            put("city", city)
            put("code", c)
            put("recipient", recipient?.trim()?.takeIf { it.isNotBlank() })
            put("updated_at", System.currentTimeMillis())
        }
        codesDb().insertWithOnConflict("pk_codes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
        return true
    }

    /**
     * Database files live under /storage/emulated/0/Extractor/ so they survive
     * uninstall and are browsable. Separate files for entry codes and visits.
     */
    private class Helper(
        private val context: Context,
        private val dbName: String,
        private val isCodes: Boolean
    ) {
        private val dir = java.io.File("/storage/emulated/0/Extractor")
        private val file = java.io.File(dir, dbName)
        private var database: SQLiteDatabase? = null

        @Synchronized
        fun writable(): SQLiteDatabase {
            database?.takeIf { it.isOpen }?.let { return it }
            val opened = openExternal() ?: openInternal()
            ensureSchema(opened)
            database = opened
            return opened
        }

        private fun openExternal(): SQLiteDatabase? {
            return try {
                if (!dir.exists() && !dir.mkdirs()) return null
                SQLiteDatabase.openOrCreateDatabase(file, null)
            } catch (_: Exception) {
                null
            }
        }

        private fun openInternal(): SQLiteDatabase {
            val old = context.getDatabasePath(dbName)
            old.parentFile?.mkdirs()
            return SQLiteDatabase.openOrCreateDatabase(old, null)
        }

        private fun ensureSchema(db: SQLiteDatabase) {
            if (isCodes) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pk_codes (
                        address_key TEXT PRIMARY KEY,
                        address TEXT NOT NULL,
                        zip TEXT,
                        city TEXT,
                        code TEXT NOT NULL,
                        recipient TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                try {
                    db.execSQL("ALTER TABLE pk_codes ADD COLUMN recipient TEXT")
                } catch (_: Exception) {
                }
            } else {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS address_log (
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
        }
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
