package com.majk.extractor.vpn

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pure-Kotlin PCAP / minimal PCAPNG reader.
 * Extracts TCP payloads from all streams (VPN is already restricted to com.opter.driver,
 * so no port filter is applied). Payloads are returned per 5-tuple direction.
 */
object PcapReader {

    data class Stream(val key: String, val payload: ByteArray)

    fun extractTcpStreams(file: File): List<Stream> {
        val bytes = file.readBytes()
        if (bytes.size < 24) return emptyList()

        val magic = bytes.int32(0)
        val little = magic == 0xA1B2C3D4.toInt() || magic == 0xA1B23C4D.toInt()
        if (!little && magic != 0xD4C3B2A1.toInt() && magic != 0x4D3CB2A1.toInt()) {
            return extractPcapNg(bytes)
        }
        val snapLen = bytes.int32(16, little).coerceAtLeast(64)

        val streams = mutableMapOf<String, ByteArrayOutputStream>()
        var off = 24
        while (off + 16 <= bytes.size) {
            val inclLen = bytes.int32(off + 8, little).coerceAtMost(snapLen)
            val pktStart = off + 16
            val pktEnd = (pktStart + inclLen).coerceAtMost(bytes.size)
            if (pktEnd > pktStart) {
                parsePacket(bytes, pktStart, pktEnd, streams)
            }
            off = pktEnd
        }
        return streams.map { Stream(it.key, it.value.toByteArray()) }
    }

    private fun parsePacket(
        buf: ByteArray,
        start: Int,
        end: Int,
        streams: MutableMap<String, ByteArrayOutputStream>
    ) {
        if (end - start < 34) return
        // Ethernet
        val etherType = buf.u16(start + 12)
        if (etherType != 0x0800) return
        val ipStart = start + 14
        if (end - ipStart < 20) return
        val verIhl = buf[ipStart].toInt() and 0xFF
        if (verIhl shr 4 != 4) return
        val ihl = (verIhl and 0x0F) * 4
        val proto = buf[ipStart + 9].toInt() and 0xFF
        if (proto != 6) return // TCP only
        val srcIp = buf.ip(ipStart + 12)
        val dstIp = buf.ip(ipStart + 16)
        val tcpStart = ipStart + ihl
        if (end - tcpStart < 20) return
        val srcPort = buf.u16(tcpStart)
        val dstPort = buf.u16(tcpStart + 2)
        val dataOff = ((buf[tcpStart + 12].toInt() and 0xFF) shr 4) * 4
        val payloadStart = tcpStart + dataOff
        if (payloadStart >= end) return
        val key = "$srcIp:$srcPort→$dstIp:$dstPort"
        streams.getOrPut(key) { ByteArrayOutputStream() }
            .write(buf, payloadStart, end - payloadStart)
    }

    private fun extractPcapNg(bytes: ByteArray): List<Stream> {
        // Minimal PCAPNG: walk blocks looking for Enhanced Packet Blocks (type 6).
        val streams = mutableMapOf<String, ByteArrayOutputStream>()
        var off = 0
        while (off + 12 <= bytes.size) {
            val blockType = bytes.int32(off)
            val blockLen = bytes.int32(off + 4)
            if (blockLen < 12 || off + blockLen > bytes.size) break
            if (blockType == 6) { // EPB
                val pktStart = off + 28 // skip block header + interface id + timestamp + captured len
                val capLen = bytes.int32(off + 20)
                val pktEnd = (pktStart + capLen).coerceAtMost(off + blockLen - 4)
                if (pktEnd > pktStart) {
                    parsePacket(bytes, pktStart, pktEnd, streams)
                }
            }
            off += blockLen
        }
        return streams.map { Stream(it.key, it.value.toByteArray()) }
    }

    private fun ByteArray.int32(off: Int, le: Boolean = true): Int {
        if (off + 4 > size) return 0
        return if (le) {
            (this[off].toInt() and 0xFF) or
                ((this[off + 1].toInt() and 0xFF) shl 8) or
                ((this[off + 2].toInt() and 0xFF) shl 16) or
                ((this[off + 3].toInt() and 0xFF) shl 24)
        } else {
            ((this[off].toInt() and 0xFF) shl 24) or
                ((this[off + 1].toInt() and 0xFF) shl 16) or
                ((this[off + 2].toInt() and 0xFF) shl 8) or
                (this[off + 3].toInt() and 0xFF)
        }
    }

    private fun ByteArray.u16(off: Int): Int =
        if (off + 2 > size) 0
        else ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun ByteArray.ip(off: Int): String =
        if (off + 4 > size) "0.0.0.0"
        else "${this[off].toInt() and 0xFF}.${this[off + 1].toInt() and 0xFF}." +
            "${this[off + 2].toInt() and 0xFF}.${this[off + 3].toInt() and 0xFF}"
}
