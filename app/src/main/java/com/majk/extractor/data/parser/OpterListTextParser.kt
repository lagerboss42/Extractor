package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.CarrierNames
import com.majk.extractor.util.WeightUtils
import java.util.Locale

/**
 * Parses Opter stop-list text as seen on screen (OCR or Accessibility dump).
 * Layout (per card, Swedish UI):
 *
 *   [Status]                    [Carrier]
 *   Avsändare: ...
 *   address zip city
 *   Mottagare: NAME              HH:MM - HH:MM
 *   ADDRESS ZIP CITY
 *   N kli / W.WW kg
 */
object OpterListTextParser {

    private val colliWeight = Regex(
        """(\d+)\s*kli\s*/\s*([\d.,]+)\s*kg""",
        RegexOption.IGNORE_CASE
    )
    private val timeRange = Regex(
        """(\d{1,2}):(\d{2})\s*[-–]\s*(\d{1,2}):(\d{2})"""
    )
    private val zipCity = Regex(
        """^(.*?)(\d{5})\s+(.+)$"""
    )

    fun parse(text: String): List<Shipment> {
        if (text.isBlank()) return emptyList()
        val normalized = text
            .replace('\u00a0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val blocks = splitBlocks(normalized)
        val out = mutableListOf<Shipment>()
        for (block in blocks) {
            parseBlock(block)?.let { out += it }
        }
        return out
    }

    private fun splitBlocks(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val blocks = mutableListOf<String>()
        val cur = mutableListOf<String>()
        val startMarkers = listOf("bekräftad", "lastad", "lossad", "nekad", "fördelad", "skapad", "meddelad")

        fun flush() {
            if (cur.isNotEmpty()) {
                blocks += cur.joinToString("\n")
                cur.clear()
            }
        }

        for (line in lines) {
            val low = line.lowercase(Locale.ROOT)
            val isStart = startMarkers.any { low == it || low.startsWith("$it ") }
            if (isStart && cur.isNotEmpty()) flush()
            cur += line
        }
        flush()
        return blocks
    }

    private fun parseBlock(block: String): Shipment? {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null

        val joined = lines.joinToString("\n")
        val low = joined.lowercase(Locale.ROOT)

        val cw = colliWeight.find(joined) ?: return null
        val colli = cw.groupValues[1].toIntOrNull() ?: return null
        val weightRaw = cw.groupValues[2].replace(',', '.').toDoubleOrNull() ?: 0.0
        val weight = WeightUtils.roundToWhole(weightRaw)

        var earliest: Int? = null
        var latest: Int? = null
        timeRange.findAll(joined).lastOrNull()?.let { m ->
            earliest = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            latest = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
        }

        val carrier = detectCarrier(lines)

        var sender: String? = null
        var recipientLine: String? = null
        for (line in lines) {
            val l = line.lowercase(Locale.ROOT)
            when {
                l.startsWith("avsändare:") || l.startsWith("avsandare:") ->
                    sender = line.substringAfter(':').trim().ifBlank { null }
                l.startsWith("mottagare:") ->
                    recipientLine = line.substringAfter(':').trim().ifBlank { null }
            }
        }

        val addressHits = lines.mapNotNull { line ->
            val m = zipCity.find(line) ?: return@mapNotNull null
            Triple(m.groupValues[1].trim(), m.groupValues[2], m.groupValues[3].trim())
        }

        val terminalHints = listOf("alltransport", "köpetorpsgatan", "kopetorpsgatan", "terminal")
        val senderIsTerminal = sender != null && terminalHints.any { sender!!.lowercase(Locale.ROOT).contains(it) }
        val recipientIsTerminal = recipientLine != null && terminalHints.any {
            recipientLine!!.lowercase(Locale.ROOT).contains(it)
        }

        val type = when {
            !senderIsTerminal && recipientIsTerminal -> ShipmentType.PICK
            low.contains("bekräftad") && !senderIsTerminal -> ShipmentType.PICK
            else -> ShipmentType.DEL
        }

        val (street, zip, city) = when (type) {
            ShipmentType.PICK -> {
                addressHits.firstOrNull { hit ->
                    terminalHints.none { hit.first.lowercase(Locale.ROOT).contains(it) }
                } ?: addressHits.firstOrNull() ?: return null
            }
            ShipmentType.DEL -> {
                addressHits.lastOrNull { hit ->
                    terminalHints.none { hit.first.lowercase(Locale.ROOT).contains(it) }
                } ?: addressHits.lastOrNull() ?: return null
            }
        }

        val recipient = when (type) {
            ShipmentType.PICK -> cleanName(sender) ?: cleanName(recipientLine) ?: ""
            ShipmentType.DEL -> cleanName(recipientLine) ?: ""
        }

        val finalRecipient = if (isRegionCarrier(carrier)) "" else recipient

        return Shipment(
            type = type,
            recipient = finalRecipient,
            address = street,
            zip = zip,
            city = city,
            colliCount = colli,
            weightKg = weight,
            portkod = null,
            comment = null,
            carrier = CarrierNames.shorten(carrier),
            sender = sender,
            earliestMinutes = earliest,
            latestMinutes = latest
        )
    }

    private fun detectCarrier(lines: List<String>): String {
        val known = listOf(
            "TNT Sverige AB", "TNT", "Region Östergötland", "Region",
            "Solar Sverige AB", "Solar", "Onninen", "Kesko", "Freja Sverige", "Freja",
            "IVM Industriverktyg"
        )
        for (line in lines.take(4)) {
            for (k in known) {
                if (line.contains(k, ignoreCase = true)) return k
            }
        }
        if (lines.isNotEmpty()) {
            val parts = lines[0].split(Regex("\\s{2,}|\t"))
            if (parts.size >= 2) return parts.last().trim()
        }
        return ""
    }

    private fun cleanName(n: String?): String? {
        if (n.isNullOrBlank()) return null
        var s = n.substringBefore('/').trim()
        s = s.replace(Regex("\\s+"), " ")
        return s.ifBlank { null }
    }

    private fun isRegionCarrier(carrier: String): Boolean {
        val s = CarrierNames.shorten(carrier)
        return s.equals("Region", ignoreCase = true)
    }
}
