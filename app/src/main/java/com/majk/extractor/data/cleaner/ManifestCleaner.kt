package com.majk.extractor.data.cleaner

import com.majk.extractor.data.db.FixedSiteStore
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.AddressUtils
import com.majk.extractor.util.CarrierNames
import com.majk.extractor.util.TextEncoding
import com.majk.extractor.util.WeightUtils

object ManifestCleaner {

    fun cleanAndConsolidate(raw: List<Shipment>): List<Shipment> {
        val cleaned = raw.map { cleanSingle(it) }
        return applyDefaultLatest(consolidate(cleaned))
    }

    /**
     * TWS with no usable latest gets 16:00. A captured time is kept only
     * inside 06:00–18:00. Before 06:00 or past 18:00 (00:00, 23:59 and
     * similar) is dropped and replaced with the 16:00 default.
     * kkböj defaults are set on the stop.
     */
    private fun applyDefaultLatest(rows: List<Shipment>): List<Shipment> {
        val earliestOk = 6 * 60
        val latestOk = 18 * 60
        val twsDefault = 16 * 60
        return rows.map { s ->
            if (!s.carrier.contains("TWS", ignoreCase = true)) s
            else {
                val latest = s.latestMinutes
                if (latest == null || latest < earliestOk || latest > latestOk) {
                    s.copy(latestMinutes = twsDefault)
                } else {
                    s
                }
            }
        }
    }

    /**
     * Fold selected kkböj stops into the consolidated manifest.
     * A kkböj stop is 1 colli. When the same address (or the same recipient and zip)
     * already has a delivery, the colli is added and the carrier becomes
     * "TWS, kkböj" so Info carries both. Otherwise the stop is appended.
     */
    fun mergeFixedStops(rows: List<Shipment>, fixed: List<Shipment>): List<Shipment> {
        if (fixed.isEmpty()) return rows
        val result = rows.toMutableList()
        val used = mutableSetOf<Int>()
        for (f in fixed) {
            val idx = findFixedMatch(result, used, f)
            if (idx != null) {
                used.add(idx)
                result[idx] = mergeFixedInto(result[idx], f)
            } else {
                result.add(f)
            }
        }
        return applyDefaultLatest(result)
    }

    private fun findFixedMatch(
        rows: List<Shipment>,
        used: Set<Int>,
        fixed: Shipment
    ): Int? {
        val fKey = addressKey(fixed)
        val byAddress = rows.indices.firstOrNull { i ->
            i !in used &&
                rows[i].type == fixed.type &&
                fKey.isNotBlank() &&
                !fKey.startsWith("|") &&
                addressKey(rows[i]) == fKey
        }
        if (byAddress != null) return byAddress
        val fZip = fixed.zip.filter { it.isDigit() }
        if (fZip.isEmpty()) return null
        return rows.indices.firstOrNull { i ->
            i !in used &&
                rows[i].type == fixed.type &&
                rows[i].zip.filter { it.isDigit() } == fZip &&
                recipientsMatch(rows[i].recipient, fixed.recipient)
        }
    }

    private fun mergeFixedInto(base: Shipment, extra: Shipment): Shipment {
        val comments = listOfNotNull(base.comment, extra.comment)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .ifBlank { null }
        return base.copy(
            colliCount = base.colliCount + extra.colliCount,
            weightKg = base.weightKg + extra.weightKg,
            portkod = base.portkod ?: extra.portkod,
            portkodRemembered = if (base.portkod != null) base.portkodRemembered else extra.portkodRemembered,
            comment = comments,
            carrier = mergeCarriers(listOf(base, extra)).ifBlank { base.carrier },
            latestMinutes = listOfNotNull(base.latestMinutes, extra.latestMinutes).minOrNull()
        )
    }

    private fun addressKey(s: Shipment): String {
        val addr = s.address.lowercase().replace(" ", "")
        val zip = s.zip.filter { it.isDigit() }
        return "$addr|$zip"
    }

    private fun recipientsMatch(a: String, b: String): Boolean {
        val na = normalizeRecipientKey(a)
        val nb = normalizeRecipientKey(b)
        if (na.isBlank() || nb.isBlank()) return false
        return na == nb || na.contains(nb) || nb.contains(na)
    }

    private fun cleanSingle(s: Shipment): Shipment {
        val spaced = AddressUtils.fixSpacing(s.address)
        val comment = s.comment?.trim()?.takeIf { it.isNotEmpty() }
        val found = s.lat?.let { la -> s.lon?.let { lo -> AddressUtils.Coords(la, lo) } }
            ?: AddressUtils.extractCoords(spaced, comment, s.recipient)
        // Prefer coordinates over street address when present (including writing into Address field).
        val address = if (found != null) found.format() else spaced
        return s.copy(
            recipient = companyOnly(TextEncoding.restoreSwedish(s.recipient)),
            sender = s.sender?.let { companyOnly(it) },
            address = address,
            city = AddressUtils.cleanCity(s.city),
            weightKg = WeightUtils.roundToWhole(s.weightKg),
            carrier = CarrierNames.shorten(s.carrier),
            portkod = s.portkod?.trim()?.takeIf { it.isNotEmpty() },
            comment = comment,
            lat = found?.lat ?: s.lat,
            lon = found?.lon ?: s.lon
        )
    }

    /** Company / site name only; strip slash-suffix notes but keep a company name after the slash. */
    private fun companyOnly(name: String): String {
        var trimmed = name.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return trimmed
        if ("/" in trimmed) {
            val parts = trimmed.split("/").map { it.trim() }.filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            for (p in parts) {
                if (out.isEmpty()) {
                    out.add(p)
                } else {
                    val prev = out.last()
                    if (prev.equals(p, ignoreCase = true)) continue
                    if (p.contains(prev, ignoreCase = true) || prev.contains(p, ignoreCase = true)) {
                        if (p.length > prev.length) out[out.lastIndex] = p
                        continue
                    }
                    out.add(p)
                }
            }
            trimmed = out.joinToString(" / ")
        }
        val beforeSlash = trimmed.substringBefore("/").trim()
        val after = trimmed.substringAfter("/", "").trim()
        if (after.isNotEmpty()) {
            val folded = normalizeAscii(after)
            val company = listOf("TOYOTA", "TMH", "TMHS", "MATERIAL HANDLING", "PORT K", "GATE K", "HALLEVAD", "BT")
            if (company.any { it in folded }) return trimmed
        }
        return beforeSlash.ifEmpty { trimmed }
    }

    private fun consolidate(list: List<Shipment>): List<Shipment> {
        val folded = foldShipments(list)
        val sites = FixedSiteStore.all()
        val buckets = sites.map { mutableListOf<Shipment>() }
        val rest = mutableListOf<Shipment>()

        for (s in folded) {
            val idx = sites.indexOfFirst { it.matches(s) }
            if (idx >= 0) buckets[idx].add(s) else rest.add(s)
        }

        val result = mutableListOf<Shipment>()
        sites.forEachIndexed { i, site ->
            val group = buckets[i]
            if (group.isEmpty()) return@forEachIndexed
            if (site.splitByType) {
                addFixedByType(result, group, site.name, site.address, site.zip, site.city)
            } else if (group.isNotEmpty()) {
                result.add(mergeFixedPort(group, site.name, site.address, site.zip, site.city))
            }
        }

        val grouped = rest.groupBy {
            Triple(
                it.type,
                normalizeRecipientKey(it.recipient),
                "${it.address.lowercase()}|${it.zip}"
            )
        }
        // Addresses that already have a scanned (package-id) row. A list-load row
        // with no id at one of those addresses is a ghost, even if the name encoding
        // differs and it landed in its own group.
        val scannedAddresses = rest
            .filter { !it.rawId.isNullOrBlank() }
            .map { "${it.type}|${it.address.lowercase()}|${it.zip}" }
            .toSet()
        for ((key, group) in grouped) {
            val scanned = group.filter { !it.rawId.isNullOrBlank() }
            val use = if (scanned.isNotEmpty()) scanned else group
            if (use.isEmpty()) continue
            val addrKey = "${key.first}|${key.third}"
            if (scanned.isEmpty() && addrKey in scannedAddresses) continue
            if (isSenderGhost(use.first())) continue
            result.add(if (use.size == 1) use.first() else mergeGroup(use))
        }

        markSharedRegionAddresses(result)
        return result.sortedWith(
            compareBy<Shipment> { it.type }
                .thenBy { it.city }
                .thenBy { it.address }
        )
    }

    private fun isSenderGhost(s: Shipment): Boolean {
        val addr = normalizeAscii(s.address)
        val name = normalizeAscii(s.recipient)
        if ("LITIUMGATAN" in addr && s.zip == "60223") return true
        if (name.endsWith("TLAND AB") && name.length < 20) return true
        return false
    }

    private fun normalizeAscii(text: String): String =
        text.uppercase()
            .replace("\u00d6", "O")
            .replace("\u00c4", "A")
            .replace("\u00c5", "A")

    private fun mergeCarriers(group: List<Shipment>): String {
        return group.map { it.carrier }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .ifBlank { "" }
    }

    private fun addFixedByType(
        result: MutableList<Shipment>,
        group: List<Shipment>,
        recipient: String,
        address: String,
        zip: String,
        city: String
    ) {
        if (group.isEmpty()) return
        val picks = group.filter { it.type == ShipmentType.PICK }
        val dels = group.filter { it.type != ShipmentType.PICK }
        if (picks.isNotEmpty()) result.add(mergeFixedPort(picks, recipient, address, zip, city))
        if (dels.isNotEmpty()) result.add(mergeFixedPort(dels, recipient, address, zip, city))
    }

    /**
     * Fixed-site merge. [address] is a plus code + city (e.g. "8493+J2P Mjölby").
     * Caller must pass a same-type group only (pickups or deliveries, never mixed).
     */
    private fun mergeFixedPort(
        group: List<Shipment>,
        recipient: String,
        address: String,
        zip: String,
        city: String
    ): Shipment {
        val type = group.first().type
        val comments = group.mapNotNull { it.comment }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .ifBlank { null }

        return Shipment(
            type = type,
            recipient = recipient,
            address = address.ifBlank { group.first().address },
            zip = zip.ifBlank { group.first().zip },
            city = city.ifBlank { group.first().city },
            colliCount = group.sumOf { it.colliCount },
            weightKg = group.sumOf { it.weightKg },
            portkod = group.mapNotNull { it.portkod }.firstOrNull(),
            comment = comments,
            carrier = mergeCarriers(group).ifBlank { "TNT" },
            lat = null,
            lon = null,
            earliestMinutes = group.mapNotNull { it.earliestMinutes }.minOrNull(),
            latestMinutes = group.mapNotNull { it.latestMinutes }.minOrNull(),
            fixedSiteName = recipient
        )
    }

    /**
     * Merge packages that share a shipment id.
     * Weight is the sum of the package weights that are present. A 0 kg package
     * adds nothing and is never given an invented weight. If only one package
     * carries a weight (even if it is not the first), that weight is the
     * shipment weight. Do not spread it and do not drop it.
     * Colli is the number of distinct packages, not the sum of a repeated
     * shipment-colli field. A master line that already reports the shipment
     * colli is not added on top of the package lines.
     * The kkböj +1 rule lives only in mergeFixedStops and is not affected.
     */
    private fun foldShipments(list: List<Shipment>): List<Shipment> {
        val withId = list.filter { !it.shipmentId.isNullOrBlank() }
        val withoutId = list.filter { it.shipmentId.isNullOrBlank() }
        val merged = withId.groupBy { "${it.type.name}|${it.shipmentId}" }.map { (_, group) ->
            if (group.size == 1) group.first()
            else mergeShipment(group)
        }
        return merged + withoutId
    }

    private fun mergeShipment(group: List<Shipment>): Shipment {
        // Prefer the package that carries the weight, so a 0 kg line
        // does not decide the address or recipient. Scan every package;
        // the weighted one may not be the first.
        val base = group.maxByOrNull { it.packageWeightKg } ?: group.maxByOrNull { it.weightKg } ?: group.first()
        // Distinct packages only. Drop a master line whose colli already
        // equals the number of other lines so it is not counted twice.
        val packageLines = if (group.size > 1) {
            val others = group.size - 1
            group.filter { it.colliCount != others || others == 0 }
        } else group
        val colli = if (packageLines.isEmpty()) group.size else packageLines.size
        val packageSum = group.sumOf { it.packageWeightKg }
        val shipmentTotal = group.maxOfOrNull { it.shipmentWeightKg } ?: 0.0
        val weight = when {
            packageSum > 0.0 -> WeightUtils.roundToWhole(packageSum)
            shipmentTotal > 0.0 -> WeightUtils.roundToWhole(shipmentTotal)
            group.size > 1 && group.all { it.weightKg == group.first().weightKg } -> group.first().weightKg
            else -> group.sumOf { it.weightKg }
        }
        return base.copy(
            colliCount = colli,
            weightKg = weight,
            portkod = group.mapNotNull { it.portkod }.firstOrNull() ?: base.portkod,
            comment = group.mapNotNull { it.comment }.filter { it.isNotBlank() }.distinct()
                .joinToString(" | ").ifBlank { base.comment },
            lgh = group.mapNotNull { it.lgh }.firstOrNull() ?: base.lgh,
            earliestMinutes = group.mapNotNull { it.earliestMinutes }.minOrNull() ?: base.earliestMinutes,
            latestMinutes = group.mapNotNull { it.latestMinutes }.minOrNull() ?: base.latestMinutes
        )
    }

    private fun mergeGroup(group: List<Shipment>): Shipment {
        val first = group.first()
        val totalColli = group.sumOf { it.colliCount }
        val totalWeight = group.sumOf { it.weightKg }

        val recipients = group.map { it.recipient }.filter { it.isNotBlank() }.distinct()
        val mergedRecipient = when {
            recipients.isEmpty() -> first.recipient
            recipients.size == 1 -> recipients.first()
            else -> recipients.joinToString(", ")
        }

        val bestPortkod = group.mapNotNull { it.portkod }.firstOrNull()
        val bestComment = group.mapNotNull { it.comment }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .ifBlank { null }

        val carriers = mergeCarriers(group)

        val coords = group.mapNotNull { s ->
            when {
                s.lat != null && s.lon != null -> AddressUtils.Coords(s.lat, s.lon)
                else -> AddressUtils.extractCoords(s.address, s.comment, s.recipient)
            }
        }.firstOrNull()

        val lat = coords?.lat ?: first.lat
        val lon = coords?.lon ?: first.lon
        val address = if (lat != null && lon != null) {
            AddressUtils.formatCoords(lat, lon)
        } else {
            first.address
        }
        return first.copy(
            recipient = mergedRecipient,
            address = address,
            colliCount = totalColli,
            weightKg = totalWeight,
            portkod = bestPortkod,
            comment = bestComment,
            carrier = carriers.ifBlank { first.carrier },
            lat = lat,
            lon = lon,
            earliestMinutes = group.mapNotNull { it.earliestMinutes }.minOrNull()
                ?: first.earliestMinutes,
            // Earliest of the latest-times when a stop has more than one shipment.
            latestMinutes = group.mapNotNull { it.latestMinutes }.minOrNull()
                ?: first.latestMinutes,
            consolidated = false
        )
    }

    /**
     * Region recipient is blank by default. Keep it only when the same address
     * has more than one distinct recipient, so those stops can be told apart.
     */
    private fun markSharedRegionAddresses(result: MutableList<Shipment>) {
        val region = result.filter { it.carrier.contains("Region", ignoreCase = true) }
        val byAddr = region.groupBy { "${it.address.lowercase()}|${it.zip}" }
        val shared = byAddr.filter { (_, g) ->
            g.map { normalizeRecipientKey(it.recipient) }.filter { it.isNotBlank() }.distinct().size > 1
        }.keys
        for (i in result.indices) {
            val s = result[i]
            if (!s.carrier.contains("Region", ignoreCase = true)) continue
            val key = "${s.address.lowercase()}|${s.zip}"
            if (key in shared) result[i] = s.copy(consolidated = true)
        }
    }

    private fun normalizeRecipientKey(recipient: String): String {
        return recipient.substringBefore("/").trim().uppercase()
    }
}
