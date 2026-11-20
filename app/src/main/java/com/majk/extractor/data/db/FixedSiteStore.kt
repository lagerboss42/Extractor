package com.majk.extractor.data.db

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One editable fixed site. Keywords are comma-separated.
 * `GE` is a whole word. `*GE*` matches anywhere. `GE*` starts with, `*GE` ends with.
 * A space is part of the phrase, so `Port K` does not match `Port F`.
 */
data class FixedSiteRule(
    val id: String,
    val name: String,
    val address: String,
    val zip: String,
    val city: String,
    val requireZip: String,
    val requireCity: String,
    val recipientKeywords: String,
    val addressKeywords: String,
    val excludeKeywords: String,
    val matchEither: Boolean,
    val splitByType: Boolean,
    /** any, pick, or del. */
    val typeFilter: String,
    val order: Int,
    /** Off keeps the row in place but skips matching. */
    val enabled: Boolean = true
) {
    fun matches(s: Shipment): Boolean {
        if (!enabled) return false
        if (typeFilter == "pick" && s.type != ShipmentType.PICK) return false
        if (typeFilter == "del" && s.type == ShipmentType.PICK) return false
        if (zip.isNotBlank() && s.zip.isNotBlank() && digits(s.zip) != digits(zip)) return false
        if (city.isNotBlank() && s.city.isNotBlank() && !cityHit(s.city, city)) return false
        val rec = fold(listOf(s.recipient, s.address, s.sender.orEmpty()).joinToString(" "))
        val addr = fold(s.address)
        val addrTight = addr.replace(" ", "")
        val excludes = keywords(excludeKeywords)
        if (excludes.any { keywordHit(it, rec) }) return false
        val recKeys = keywords(recipientKeywords)
        val addrKeys = keywords(addressKeywords)
        if (recKeys.isEmpty() && addrKeys.isEmpty()) return false
        val recHit = recKeys.any { keywordHit(it, rec) }
        val addrHit = addrKeys.any { addressHit(it, addr, addrTight) }
        return if (matchEither) {
            (recKeys.isNotEmpty() && recHit) || (addrKeys.isNotEmpty() && addrHit)
        } else {
            (recKeys.isEmpty() || recHit) && (addrKeys.isEmpty() || addrHit)
        }
    }

    companion object {
        fun keywords(raw: String): List<String> =
            raw.split(',')
                .map { it.trim().trim('"').trim() }
                .filter { it.isNotEmpty() && it != "*" }

        fun addressHit(keyword: String, addr: String, tight: String): Boolean {
            if (keywordHit(keyword, addr) || keywordHit(keyword, tight)) return true
            val core = fold(keyword).trim('*').replace(" ", "")
            if (core.isEmpty()) return false
            val i = tight.indexOf(core)
            if (i < 0) return false
            val after = i + core.length
            return after >= tight.length || !tight[after].isDigit()
        }

        fun keywordHit(keyword: String, text: String): Boolean {
            val k = fold(keyword)
            val core = k.trim('*')
            if (core.isEmpty()) return false
            val starts = k.startsWith("*")
            val ends = k.endsWith("*")
            return when {
                starts && ends -> core in text
                ends && !starts -> Regex("(?:^|[^a-z0-9])${Regex.escape(core)}").containsMatchIn(text)
                starts && !ends -> Regex("${Regex.escape(core)}(?:[^a-z0-9]|$)").containsMatchIn(text)
                else -> Regex("(?:^|[^a-z0-9])${Regex.escape(core)}(?:[^a-z0-9]|$)").containsMatchIn(text)
            }
        }

        fun fold(raw: String): String =
            raw.uppercase()
                .replace("\u00d6", "O").replace("\u00c4", "A").replace("\u00c5", "A")
                .replace("Ö", "O").replace("Ä", "A").replace("Å", "A")
                .lowercase()
                .replace("ö", "o").replace("ä", "a").replace("å", "a")
                .replace("oe", "o")

        fun cityHit(stopCity: String, required: String): Boolean {
            val a = fold(stopCity)
            val b = fold(required)
            return b.isNotBlank() && b in a
        }

        fun digits(raw: String): String = raw.filter { it.isDigit() }
    }
}

/**
 * Editable fixed-site list. Seeded from the old hardcoded rules on first launch.
 * File: /storage/emulated/0/Extractor/fixed_sites.json
 */
object FixedSiteStore {

    @Volatile
    var revision by mutableStateOf(0)
        private set

    private var file: java.io.File? = null
    private var cached: List<FixedSiteRule>? = null

    fun init(context: Context) {
        if (file != null) return
        val dir = java.io.File("/storage/emulated/0/Extractor")
        if (!dir.exists()) dir.mkdirs()
        val external = java.io.File(dir, "fixed_sites.json")
        file = if (dir.exists() && dir.canWrite()) external
        else java.io.File(context.filesDir, "fixed_sites.json")
        load()
    }

    fun all(): List<FixedSiteRule> = cached.orEmpty().sortedBy { it.order }

    fun saveAll(list: List<FixedSiteRule>) {
        val ordered = list.mapIndexed { i, s -> s.copy(order = i) }
        cached = ordered
        write(ordered)
        revision++
    }

    fun upsert(rule: FixedSiteRule) {
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) current[idx] = rule else current.add(rule)
        saveAll(current)
    }

    fun delete(id: String) {
        saveAll(all().filter { it.id != id })
    }

    fun move(id: String, up: Boolean, persist: Boolean = true) {
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        val swap = if (up) idx - 1 else idx + 1
        if (swap !in current.indices) return
        val item = current.removeAt(idx)
        current.add(swap, item)
        if (persist) saveAll(current) else stage(current)
    }

    /** Memory only. Disk write waits until the drag ends. */
    private fun stage(list: List<FixedSiteRule>) {
        cached = list.mapIndexed { i, s -> s.copy(order = i) }
        revision++
    }

    fun persist() {
        write(cached.orEmpty())
    }

    fun match(s: Shipment): FixedSiteRule? = all().firstOrNull { it.matches(s) }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) {
            val seed = seed()
            cached = seed
            write(seed)
            return
        }
        cached = try {
            val parsed = parse(f.readText())
            val seeded = setOf("59535", "59530", "58580", "59631")
            parsed.map { if (FixedSiteRule.digits(it.zip) in seeded) it.copy(zip = "") else it }
        } catch (_: Exception) {
            seed()
        }
        write(cached.orEmpty())
    }

    private fun write(list: List<FixedSiteRule>) {
        val f = file ?: return
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("address", s.address)
                put("zip", s.zip)
                put("city", s.city)
                put("requireZip", s.requireZip)
                put("requireCity", s.requireCity)
                put("recipientKeywords", s.recipientKeywords)
                put("addressKeywords", s.addressKeywords)
                put("excludeKeywords", s.excludeKeywords)
                put("matchEither", s.matchEither)
                put("splitByType", s.splitByType)
                put("typeFilter", s.typeFilter)
                put("order", s.order)
                put("enabled", s.enabled)
            })
        }
        f.parentFile?.mkdirs()
        f.writeText(arr.toString())
    }

    private fun parse(raw: String): List<FixedSiteRule> {
        val arr = JSONArray(raw)
        val out = mutableListOf<FixedSiteRule>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                FixedSiteRule(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name"),
                    address = o.optString("address"),
                    zip = o.optString("zip"),
                    city = o.optString("city"),
                    requireZip = o.optString("requireZip"),
                    requireCity = o.optString("requireCity"),
                    recipientKeywords = o.optString("recipientKeywords"),
                    addressKeywords = o.optString("addressKeywords"),
                    excludeKeywords = o.optString("excludeKeywords"),
                    matchEither = o.optBoolean("matchEither", false),
                    splitByType = o.optBoolean("splitByType", true),
                    typeFilter = o.optString("typeFilter", "any"),
                    order = o.optInt("order", i),
                    enabled = o.optBoolean("enabled", true)
                )
            )
        }
        return out.sortedBy { it.order }
    }

    private fun seed(): List<FixedSiteRule> = listOf(
        site("Port F", "84C3+H7H Mjölby", "", "Mjölby", "port f, gate f, truckhuset", "truckgatan 2", "", true),
        site("Port D", "8496+2RC Mjölby", "", "Mjölby", "port d, gate d", "", "", false),
        site("Port K", "8493+J2P Mjölby", "", "Mjölby", "toyota, tmh, tmhs, \"Port K\", \"Gate K\", \"material handling\", hallevad, bt", "", "port f, gate f, truckhuset", false),
        site("Cloetta", "GF7X+3V Ljungsbro", "", "Ljungsbro", "cloetta", "", "", false, type = "pick"),
        site("Cloetta Godsmottagning", "GG72+6F Ljungsbro", "", "Ljungsbro", "cloetta", "", "", false, type = "del"),
        site("ASM Foods", "84CJ+J3R Mjölby", "", "Mjölby", "asm", "kyrkogatan, magasinsgatan", "", false),
        site("Nelleman", "93QW+43H Skänninge", "", "Skänninge", "nelleman", "", "", false),
        site("GE", "93QV+9M2 Skänninge", "", "Skänninge", "GE, \"GE ENERGY\"", "", "", false),
        site("Universal Power", "93PW+J25 Skänninge", "", "Skänninge", "universal", "", "", false),
        site("Runsven", "93JX+Q98 Skänninge", "", "Skänninge", "runsven", "borgmästaregatan 23, mjölbygatan 25", "", true),
        site("Vestas", "93QR+5H8 Skänninge", "", "Skänninge", "vestas", "", "", false),
        site("Aebi", "93PV+H3J Skänninge", "", "Skänninge", "aebi", "", "", false)
    )

    private fun site(
        name: String,
        address: String,
        zip: String,
        city: String,
        recipient: String,
        addressKeys: String,
        exclude: String,
        either: Boolean,
        type: String = "any",
        requireZip: String = "",
        requireCity: String = ""
    ) = FixedSiteRule(
        id = UUID.randomUUID().toString(),
        name = name,
        address = address,
        zip = zip,
        city = city,
        requireZip = requireZip,
        requireCity = requireCity,
        recipientKeywords = recipient,
        addressKeywords = addressKeys,
        excludeKeywords = exclude,
        matchEither = either,
        splitByType = true,
        typeFilter = type,
        order = 0
    )
}
