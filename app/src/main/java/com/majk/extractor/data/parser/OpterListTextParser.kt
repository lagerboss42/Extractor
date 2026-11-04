package com.majk.extractor.data.parser

import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.util.CarrierNames
import com.majk.extractor.util.TextEncoding
import com.majk.extractor.util.WeightUtils
import java.util.Locale

/**
 * Parses Opter stop-list text from OCR or Accessibility.
 * Filters terminal (Alltransport / Köpetorpsgatan) rows and repairs common OCR glitches.
 */
object OpterListTextParser {

    private val colliWeight = Regex(
        """(\d+)\s*kli\s*/\s*([\d.,]+)\s*kg""",
        RegexOption.IGNORE_CASE
    )
    private val timeRange = Regex(
        """(\d{1,2}):(\d{2})\s*[-–]\s*(\d{1,2}):(\d{2})"""
    )
    private val timeStartOnly = Regex(
        """(\d{1,2}):(\d{2})\s*[-–]\s*$"""
    )
    private val zipCity = Regex(
        """^(.*?)[,\s]+(\d{5})\s+(.+)$"""
    )

    private val terminalNameHints = listOf(
        "alltransport", "kopetorpsgatan", "köpetorpsgatan", "terminal link"
    )
    private val junkName = Regex(
        """^(onatan|test|ut|in|null|unknown)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): List<Shipment> {
        if (text.isBlank()) return emptyList()
        val fixed = TextEncoding.fixSwedish(text.replace('\u00a0', ' '))
            .replace("MJ?LBY", "MJÖLBY", ignoreCase = true)
            .replace("MJOELBY", "MJÖLBY", ignoreCase = true)
            .replace("Mjoelby", "Mjölby")
            .replace("S?DRA", "SÖDRA", ignoreCase = true)
            .replace("STRANDV?GEN", "STRANDVÄGEN", ignoreCase = true)
            .replace("V?GEN", "VÄGEN", ignoreCase = true)
            .replace("PORK K", "PORT K", ignoreCase = true)
            .replace("BORGMAESTARE", "BORGMÄSTARE", ignoreCase = true)
            .replace("ALBACKSVAEGEN", "ÅLBACKSVÄGEN", ignoreCase = true)
            .replace("ALBACKSVAGEN", "ÅLBACKSVÄGEN", ignoreCase = true)
            .replace("IDROTTSVAGEN", "IDROTTSVÄGEN", ignoreCase = true)
            .replace("FORENINGSGATAN", "FÖRENINGSGATAN", ignoreCase = true)
            .replace("SKANNINGE", "SKÄNNINGE", ignoreCase = true)

        val blocks = splitBlocks(fixed)
        val out = mutableListOf<Shipment>()
        for (block in blocks) {
            parseBlock(block)?.let { s ->
                if (!isTerminalStop(s) && !isJunk(s)) out += s
            }
        }
        return out
    }

    private fun isTerminalStop(s: Shipment): Boolean {
        val blob = "${s.recipient} ${s.address} ${s.sender.orEmpty()}".lowercase(Locale.ROOT)
        return terminalNameHints.any { it in TextEncoding.foldAscii(blob) }
    }

    private fun isJunk(s: Shipment): Boolean {
        if (s.zip.filter { it.isDigit() }.length != 5) return true
        if (s.address.length < 3) return true
        if (junkName.containsMatchIn(s.recipient)) return true
        if (s.recipient.length in 1..2) return true
        if (s.recipient.matches(Regex("""[a-z]{2,8}\s+[A-Z]$"""))) return true
        return false
    }

    private fun splitBlocks(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val blocks = mutableListOf<String>()
        val cur = mutableListOf<String>()
        val startMarkers = listOf(
            "bekräftad", "lastad", "lossad", "nekad", "fördelad", "skapad", "meddelad"
        )

        fun flush() {
            if (cur.isNotEmpty()) {
                blocks += cur.joinToString("\n")
                cur.clear()
            }
        }

        for (line in lines) {
            val low = line.lowercase(Locale.ROOT)
            val isStart = startMarkers.any {
                low == it || low.startsWith("$it ") || low.startsWith("$it\t")
            }
            val mottagareStarts = low.startsWith("mottagare:") && cur.any {
                colliWeight.containsMatchIn(it)
            }
            if ((isStart || mottagareStarts) && cur.isNotEmpty()) flush()
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
        } ?: timeStartOnly.find(joined)?.let { m ->
            earliest = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
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
                    recipientLine = line.substringAfter(':').trim()
                        .substringBefore("  ")
                        .trim()
                        .ifBlank { null }
            }
        }
        recipientLine = recipientLine?.replace(Regex("""\.{2,}$"""), "")?.trim()

        val addressHits = lines.mapNotNull { line ->
            val ll = line.lowercase(Locale.ROOT)
            if (ll.startsWith("avsändare:") || ll.startsWith("avsandare:") ||
                ll.startsWith("mottagare:") || ll.startsWith("lastad") ||
                ll.startsWith("bekräftad")
            ) return@mapNotNull null
            if (colliWeight.containsMatchIn(line)) return@mapNotNull null
            val m = zipCity.find(line) ?: return@mapNotNull null
            var street = m.groupValues[1].trim().trim(',').trim()
            val zip = m.groupValues[2]
            var city = m.groupValues[3].trim()
            city = city.replace(Regex("""\s+0$"""), "")
            if (street.isBlank()) return@mapNotNull null
            Triple(street, zip, city)
        }

        val fold = { s: String -> TextEncoding.foldAscii(s.lowercase(Locale.ROOT)) }
        val senderIsTerminal = sender != null && terminalNameHints.any {
            fold(sender!!).contains(it.replace("ö", "o"))
        }
        val recipientIsTerminal = recipientLine != null && terminalNameHints.any {
            fold(recipientLine!!).contains(it.replace("ö", "o"))
        }

        val type = when {
            !senderIsTerminal && recipientIsTerminal -> ShipmentType.PICK
            low.contains("bekräftad") && sender != null && !senderIsTerminal -> ShipmentType.PICK
            else -> ShipmentType.DEL
        }

        val customerAddresses = addressHits.filter { hit ->
            terminalNameHints.none { fold(hit.first).contains(it.replace("ö", "o")) }
        }
        val (street, zip, city) = when (type) {
            ShipmentType.PICK -> customerAddresses.firstOrNull() ?: addressHits.firstOrNull() ?: return null
            ShipmentType.DEL -> customerAddresses.lastOrNull() ?: addressHits.lastOrNull() ?: return null
        }

        if (terminalNameHints.any { fold(street).contains(it.replace("ö", "o")) }) return null

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
            city = city.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            },
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
            "TNT Sverige AB", "TNT",
            "Region Östergötland", "Region",
            "Solar Sverige AB", "Solar",
            "Onninen", "Kesko",
            "Freja Sverige", "Freja",
            "TWS Logistics", "TWS",
            "Packoplock Scandinavia AB", "Packoplock",
            "IVM Industriverktyg"
        )
        for (line in lines.take(5)) {
            for (k in known) {
                if (line.contains(k, ignoreCase = true)) return k
            }
        }
        return ""
    }

    private fun cleanName(n: String?): String? {
        if (n.isNullOrBlank()) return null
        var s = n.substringBefore('/').trim()
        s = s.replace(Regex("""\s+"""), " ")
        s = s.replace(Regex("""\.{2,}$"""), "").trim()
        if (s.length <= 2) return null
        if (junkName.containsMatchIn(s)) return null
        return s
    }

    private fun isRegionCarrier(carrier: String): Boolean {
        return CarrierNames.shorten(carrier).equals("Region", ignoreCase = true)
    }
}
