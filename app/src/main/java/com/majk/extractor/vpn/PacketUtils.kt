package com.majk.extractor.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IPv4 / TCP helpers for building packets back into the TUN.
 */
object PacketUtils {

    fun readU16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    fun readU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    fun writeU16(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    fun writeU32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = ((value ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 3] = (value and 0xFF).toByte()
    }

    fun ipToString(buf: ByteArray, off: Int): String =
        "${buf[off].toInt() and 0xFF}.${buf[off + 1].toInt() and 0xFF}." +
            "${buf[off + 2].toInt() and 0xFF}.${buf[off + 3].toInt() and 0xFF}"

    fun ipToBytes(ip: String): ByteArray {
        val p = ip.split(".")
        return byteArrayOf(
            p[0].toInt().toByte(),
            p[1].toInt().toByte(),
            p[2].toInt().toByte(),
            p[3].toInt().toByte()
        )
    }

    fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun buildIpv4Tcp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        window: Int = 65535
    ): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payload.size
        val packet = ByteArray(totalLen)

        packet[0] = 0x45.toByte()
        packet[1] = 0
        writeU16(packet, 2, totalLen)
        writeU16(packet, 4, (System.currentTimeMillis() and 0xFFFF).toInt())
        writeU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 6
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)
        writeU16(packet, 10, checksum(packet, 0, 20))

        val tcpOff = 20
        writeU16(packet, tcpOff, srcPort)
        writeU16(packet, tcpOff + 2, dstPort)
        writeU32(packet, tcpOff + 4, seq)
        writeU32(packet, tcpOff + 8, ack)
        packet[tcpOff + 12] = 0x50.toByte()
        packet[tcpOff + 13] = flags.toByte()
        writeU16(packet, tcpOff + 14, window)
        writeU16(packet, tcpOff + 18, 0)

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOff + 20, payload.size)
        }

        val tcpLen = tcpHeaderLen + payload.size
        val pseudo = ByteArray(12 + tcpLen)
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 6
        writeU16(pseudo, 10, tcpLen)
        System.arraycopy(packet, tcpOff, pseudo, 12, tcpLen)
        writeU16(packet, tcpOff + 16, checksum(pseudo))

        return packet
    }

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}
