package com.majk.extractor.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.majk.extractor.data.model.Shipment
import java.time.LocalDate

data class PkEntry(
    val id: Long,
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
            if (isKkb(s)) return@map s
            val recKey = recipientKey(s.recipient)
            val addrKey = addressKey(s)
            if (recKey.isBlank() && addrKey.isBlank()) return@map s

            val (found, cleanedComment) = PortkodExtractor.extract(s.portkod, s.comment)
            if (found != null) upsertCode(database, s, found, now)

            val saved = lookupCodes(database, s)
            val codes = buildList {
                if (found != null) add(found)
                addAll(saved)
            }.distinct().take(4)
            if (codes.isEmpty()) {
                if (found != null) s.copy(comment = cleanedComment) else s
            } else {
                s.copy(
                    portkod = codes.joinToString(", "),
                    comment = if (found != null) cleanedComment else s.comment,
                    portkodRemembered = saved.isNotEmpty()
                )
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

    /**
     * Visit identity:
     *  - Port K / D / F and Cloetta: one ID per consolidated site (and type).
     *  - Region: group by address.
     *  - Everything else: one group per unique recipient.
     * Every shipment in the group adds to the count and weight.
     */
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
            SELECT id, address, zip, city, code, recipient, updated_at
            FROM pk_codes
            WHERE address LIKE ? OR zip LIKE ? OR city LIKE ? OR code LIKE ? OR recipient LIKE ?
            ORDER BY COALESCE(recipient, address) COLLATE NOCASE, code COLLATE NOCASE
        """.trimIndent()
        val out = mutableListOf<PkEntry>()
        codesDb().rawQuery(sql, arrayOf(q, q, q, q, q)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PkEntry(
                        id = c.getLong(0),
                        address = c.getString(1),
                        zip = c.getString(2).orEmpty(),
                        city = c.getString(3).orEmpty(),
                        code = c.getString(4),
                        recipient = c.getString(5)?.takeIf { it.isNotBlank() },
                        updatedAt = c.getLong(6)
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

    fun updateCode(
        id: Long,
        code: String,
        recipient: String?,
        address: String,
        zip: String,
        city: String
    ): Boolean {
        val c = code.trim()
        val rec = recipient?.trim()?.takeIf { it.isNotBlank() }
        val a = address.trim()
        val z = zip.trim()
        val cityClean = city.trim()
        if (id <= 0 || c.isEmpty()) return false
        if (!c.matches(Regex("^[A-Za-z0-9*#]+$"))) return false
        if (rec == null && (a.isEmpty() || z.isEmpty())) return false
        val key = if (a.isEmpty()) "" else addressKey(a, z)
        if (rec == null && key.isBlank()) return false
        val values = ContentValues().apply {
            put("address_key", key)
            put("address", a)
            put("zip", z)
            put("city", cityClean)
            put("code", c)
            put("recipient", rec)
            put("recipient_key", recipientKey(rec.orEmpty()))
            put("updated_at", System.currentTimeMillis())
        }
        return try {
            val n = codesDb().update("pk_codes", values, "id = ?", arrayOf(id.toString()))
            if (n > 0) bump()
            n > 0
        } catch (_: Exception) {
            false
        }
    }

    fun deleteCode(id: Long): Boolean {
        if (id <= 0) return false
        val n = codesDb().delete("pk_codes", "id = ?", arrayOf(id.toString()))
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
        oldAddress: String,
        oldZip: String,
        oldDate: String,
        address: String,
        zip: String,
        city: String,
        shipments: Int,
        totalWeight: Int,
        newDate: String
    ): Boolean {
        val key = visitKeyForStored(oldAddress, oldZip, oldDate) ?: return false
        val date = newDate.trim()
        val addr = address.trim()
        val z = zip.trim()
        val cityClean = city.trim()
        if (date.isEmpty() || !date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return false
        if (addr.isEmpty() || shipments < 0 || totalWeight < 0) return false
        val database = visitsDb()
        if (date != oldDate && visitExists(database, key, date)) return false
        val values = ContentValues().apply {
            put("address", addr)
            put("zip", z)
            put("city", cityClean)
            put("shipments", shipments)
            put("total_weight", totalWeight)
            put("visit_date", date)
        }
        return try {
            val n = database.update(
                "address_log",
                values,
                "address_key = ? AND visit_date = ?",
                arrayOf(key, oldDate)
            )
            if (n > 0) bump()
            n > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun visitExists(database: SQLiteDatabase, key: String, date: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM address_log WHERE address_key = ? AND visit_date = ? LIMIT 1",
            arrayOf(key, date)
        ).use { it.moveToFirst() }

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

    /** Resolve the stored visit key from the label the UI shows, so edits still hit the row. */
    private fun visitKeyForStored(address: String, zip: String, date: String): String? {
        visitsDb().rawQuery(
            "SELECT address_key FROM address_log WHERE address = ? AND zip = ? AND visit_date = ? LIMIT 1",
            arrayOf(address, zip, date)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun upsertCode(database: SQLiteDatabase, s: Shipment, code: String, now: Long) {
        val rec = s.recipient.takeIf { it.isNotBlank() }
        val values = ContentValues().apply {
            put("address_key", addressKey(s))
            put("address", s.address)
            put("zip", s.zip)
            put("city", s.city)
            put("recipient", rec)
            put("recipient_key", recipientKey(s.recipient))
            put("code", code)
            put("updated_at", now)
        }
        database.insertWithOnConflict("pk_codes", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Recipient entry: recipient must match, and city must match when the entry has one.
     * Address-only entry: address + zip only.
     * At most 4 codes.
     */
    private fun lookupCodes(database: SQLiteDatabase, s: Shipment): List<String> {
        val wantRec = recipientKey(s.recipient)
        val wantCity = cityKey(s.city)
        val wantAddr = addressKey(s)
        val found = linkedSetOf<String>()
        database.rawQuery(
            "SELECT code, recipient_key, city, address, zip FROM pk_codes ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext() && found.size < 4) {
                val code = c.getString(0)?.trim().orEmpty()
                if (code.isEmpty()) continue
                val rec = c.getString(1).orEmpty()
                val city = cityKey(c.getString(2).orEmpty())
                val addr = addressKey(c.getString(3).orEmpty(), c.getString(4).orEmpty())
                val hit = if (rec.isNotBlank()) {
                    rec == wantRec && (city.isBlank() || city == wantCity)
                } else {
                    wantAddr.isNotBlank() && addr == wantAddr
                }
                if (hit) found.add(code)
            }
        }
        return found.toList()
    }

    private fun bump() {
        revision++
    }

    fun addressKey(s: Shipment): String = addressKey(s.address, s.zip)

    fun addressKey(address: String, zip: String): String {
        // Drop a trailing entrance letter: 13A and 13 are the same door.
        val a = foldKey(address).replace(Regex("(\\d)[a-z]$"), "$1")
        val z = zip.filter { it.isDigit() }
        if (a.length < 3) return ""
        return "$a|$z"
    }

    fun recipientKey(name: String): String = foldKey(name)

    fun cityKey(city: String): String =
        foldKey(city).replace(Regex("\\d+$"), "")

    private fun foldKey(raw: String): String {
        val nfd = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
        return nfd.lowercase()
            .replace("å", "a").replace("ä", "a").replace("ö", "o").replace("é", "e")
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun isKkb(s: Shipment): Boolean =
        s.carrier.equals("kkb\u00f6j", ignoreCase = true)

    /**
     * Manual entry-code add. Code is alphanumeric plus *#.
     * Recipient is optional; when present it is the display name.
     */
    fun insertManualCode(
        code: String,
        recipient: String?,
        address: String,
        zip: String,
        city: String = ""
    ): Boolean {
        val c = code.trim()
        val rec = recipient?.trim()?.takeIf { it.isNotBlank() }
        val a = address.trim()
        val z = zip.trim()
        val cityClean = city.trim()
        if (c.isEmpty()) return false
        if (!c.matches(Regex("^[A-Za-z0-9*#]+$"))) return false
        if (rec == null && (a.isEmpty() || z.isEmpty())) return false
        val key = if (a.isEmpty()) "" else addressKey(a, z)
        if (rec == null && key.isBlank()) return false
        val values = ContentValues().apply {
            put("address_key", key)
            put("address", a)
            put("zip", z)
            put("city", cityClean)
            put("code", c)
            put("recipient", rec)
            put("recipient_key", recipientKey(rec.orEmpty()))
            put("updated_at", System.currentTimeMillis())
        }
        val row = codesDb().insertWithOnConflict("pk_codes", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (row >= 0) bump()
        return row >= 0
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
                ensureCodes(db)
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

        private fun ensureCodes(db: SQLiteDatabase) {
            if (tableExists(db, "pk_codes") && !columnExists(db, "pk_codes", "id")) {
                if (!columnExists(db, "pk_codes", "recipient")) {
                    db.execSQL("ALTER TABLE pk_codes ADD COLUMN recipient TEXT")
                }
                if (!columnExists(db, "pk_codes", "city")) {
                    db.execSQL("ALTER TABLE pk_codes ADD COLUMN city TEXT")
                }
                db.execSQL("ALTER TABLE pk_codes RENAME TO pk_codes_old")
                createCodes(db)
                db.execSQL(
                    """
                    INSERT INTO pk_codes (address_key, address, zip, city, code, recipient, recipient_key, updated_at)
                    SELECT address_key, address, IFNULL(zip, ''), IFNULL(city, ''), code,
                           recipient, '', updated_at
                    FROM pk_codes_old
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE pk_codes_old")
                fillRecipientKeys(db)
            } else {
                createCodes(db)
                if (!columnExists(db, "pk_codes", "recipient_key")) {
                    db.execSQL("ALTER TABLE pk_codes ADD COLUMN recipient_key TEXT NOT NULL DEFAULT ''")
                    fillRecipientKeys(db)
                }
            }
            rekeyAddresses(db)
        }

        private fun createCodes(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pk_codes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    address_key TEXT NOT NULL DEFAULT '',
                    address TEXT,
                    zip TEXT,
                    city TEXT,
                    code TEXT NOT NULL,
                    recipient TEXT,
                    recipient_key TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS pk_codes_unique
                ON pk_codes(address_key, recipient_key, code)
                """.trimIndent()
            )
        }

        private fun fillRecipientKeys(db: SQLiteDatabase) {
            db.rawQuery("SELECT id, recipient FROM pk_codes", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val key = recipientKey(c.getString(1).orEmpty())
                    val values = ContentValues().apply { put("recipient_key", key) }
                    db.update("pk_codes", values, "id = ?", arrayOf(id.toString()))
                }
            }
        }

        private fun rekeyAddresses(db: SQLiteDatabase) {
            db.rawQuery("SELECT id, address, zip FROM pk_codes", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val key = addressKey(c.getString(1).orEmpty(), c.getString(2).orEmpty())
                    val values = ContentValues().apply { put("address_key", key) }
                    db.update("pk_codes", values, "id = ?", arrayOf(id.toString()))
                }
            }
        }

        private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(name)
            ).use { it.moveToFirst() }

        private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean =
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (c.getString(idx) == column) return true
                }
                false
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
