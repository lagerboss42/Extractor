package com.majk.extractor.vpn

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Userspace TCP relay: app (via TUN) ↔ remote host (real socket).
 *
 * Correctness rules (RFC 793):
 *  - Sequence numbers are 32-bit modular. All comparisons use modular arithmetic.
 *  - Only in-order bytes are forwarded to the remote socket.
 *  - Retransmissions of already-ACKed data are ignored (re-ACK only).
 *  - Out-of-order segments are buffered (small window) and flushed when the gap fills.
 *  - Advertised window reflects remaining reorder buffer space.
 */
class TcpSession(
    private val vpn: VpnService,
    private val clientIp: ByteArray,
    private val clientPort: Int,
    private val serverIp: ByteArray,
    private val serverPort: Int,
    private val clientIsn: Long,
    private val writeToTun: (ByteArray) -> Unit,
    private val onStreamData: (session: TcpSession, data: ByteArray, fromClient: Boolean) -> Unit,
    private val onClosed: (String) -> Unit
) {
    private val tag = "TcpSession"
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var inp: InputStream? = null

    private var serverSeq: Long = Random.nextInt().toLong() and 0xFFFFFFFFL
    private var clientSeq: Long = (clientIsn + 1) and 0xFFFFFFFFL

    private val alive = AtomicBoolean(true)
    private var readerThread: Thread? = null

    /** Accumulated payloads for parsing. Capped to avoid OOM on long sessions. */
    val clientBuffer = StringBuilder()
    val serverBuffer = StringBuilder()
    private val maxBufferChars = 2_000_000

    /** Out-of-order client segments: seq -> payload. */
    private val reorder = TreeMap<Long, ByteArray>()
    private val maxReorderBytes = 64 * 1024
    private var reorderBytes = 0

    /** Guard so a retransmitted FIN does not advance sequence twice. */
    private var finReceived = false

    val remotePort: Int get() = serverPort
    val localPort: Int get() = clientPort

    val key: String
        get() = "${PacketUtils.ipToString(clientIp, 0)}:$clientPort-" +
            "${PacketUtils.ipToString(serverIp, 0)}:$serverPort"

    val involvesPort81: Boolean
        get() = serverPort == 81 || clientPort == 81

    /** Current advertised window: remaining reorder capacity. */
    private fun advertisedWindow(): Int =
        (maxReorderBytes - reorderBytes).coerceIn(1, 65535)

    fun acceptSyn() {
        try {
            val s = Socket()
            vpn.protect(s)
            s.tcpNoDelay = true
            // Idle timeout: Opter list loads can pause. 30s was killing live scans.
            s.soTimeout = 120_000
            s.connect(
                InetSocketAddress(PacketUtils.ipToString(serverIp, 0), serverPort),
                10_000
            )
            socket = s
            out = s.getOutputStream()
            inp = s.getInputStream()

            val synAck = PacketUtils.buildIpv4Tcp(
                srcIp = serverIp,
                dstIp = clientIp,
                srcPort = serverPort,
                dstPort = clientPort,
                seq = serverSeq,
                ack = clientSeq,
                flags = PacketUtils.SYN or PacketUtils.ACK,
                window = advertisedWindow()
            )
            writeToTun(synAck)
            serverSeq = (serverSeq + 1) and 0xFFFFFFFFL

            readerThread = Thread({ readRemoteLoop() }, "tcp-$serverPort").also { it.start() }
            Log.i(tag, "Connected $key")
        } catch (e: Exception) {
            Log.w(tag, "Connect failed $key: ${e.message}")
            sendRst()
            close()
        }
    }

    fun onClientSegment(seq: Long, ack: Long, flags: Int, payload: ByteArray) {
        if (!alive.get()) return

        if (flags and PacketUtils.RST != 0) {
            close()
            return
        }

        if (payload.isNotEmpty()) {
            try {
                handleClientPayload(seq, payload)
            } catch (e: Exception) {
                Log.w(tag, "Write to remote failed: ${e.message}")
                close()
                return
            }
        }

        if (flags and PacketUtils.FIN != 0 && !finReceived) {
            finReceived = true
            clientSeq = (clientSeq + 1) and 0xFFFFFFFFL
            try {
                socket?.shutdownOutput()
            } catch (_: Exception) {}
            sendAck(PacketUtils.FIN or PacketUtils.ACK)
            serverSeq = (serverSeq + 1) and 0xFFFFFFFFL
        }
    }

    /**
     * Accept client payload using modular sequence arithmetic.
     * In-order data is forwarded immediately. Gaps are buffered.
     * Already-ACKed data is ignored (pure retransmit).
     */
    private fun handleClientPayload(seq: Long, payload: ByteArray) {
        val expected = clientSeq
        val end = (seq + payload.size) and 0xFFFFFFFFL

        // Pure retransmit: entirely before expected.
        if (seqLe(end, expected)) {
            sendAck(PacketUtils.ACK)
            return
        }

        // Trim already-ACKed prefix if segment overlaps expected.
        val fresh = if (seqLt(seq, expected)) {
            val skip = seqDelta(expected, seq).toInt().coerceIn(0, payload.size)
            payload.copyOfRange(skip, payload.size)
        } else {
            payload
        }
        val freshSeq = if (seqLt(seq, expected)) expected else seq

        if (fresh.isEmpty()) {
            sendAck(PacketUtils.ACK)
            return
        }

        if (freshSeq == expected) {
            // In order — forward and advance.
            forwardToRemote(fresh)
            clientSeq = (expected + fresh.size) and 0xFFFFFFFFL
            flushReorder()
            sendAck(PacketUtils.ACK)
        } else if (seqLt(expected, freshSeq)) {
            // Future segment — buffer if we have room.
            if (reorderBytes + fresh.size <= maxReorderBytes && freshSeq !in reorder) {
                reorder[freshSeq] = fresh
                reorderBytes += fresh.size
            }
            // ACK what we have so far; client will retransmit the gap.
            sendAck(PacketUtils.ACK)
        }
    }

    /** Flush any buffered segments that are now contiguous with clientSeq. */
    private fun flushReorder() {
        while (true) {
            val next = reorder.remove(clientSeq) ?: break
            reorderBytes -= next.size
            forwardToRemote(next)
            clientSeq = (clientSeq + next.size) and 0xFFFFFFFFL
        }
    }

    private fun forwardToRemote(data: ByteArray) {
        out?.write(data)
        out?.flush()
        onStreamData(this, data, true)
    }

    /** Append to a parse buffer, dropping the oldest half if it exceeds the cap. */
    fun appendBounded(buf: StringBuilder, text: String) {
        buf.append(text)
        if (buf.length > maxBufferChars) {
            buf.delete(0, buf.length - maxBufferChars / 2)
        }
    }

    private fun sendAck(flags: Int) {
        val pkt = PacketUtils.buildIpv4Tcp(
            srcIp = serverIp,
            dstIp = clientIp,
            srcPort = serverPort,
            dstPort = clientPort,
            seq = serverSeq,
            ack = clientSeq,
            flags = flags,
            window = advertisedWindow()
        )
        writeToTun(pkt)
    }

    private fun readRemoteLoop() {
        // Coalesce small reads into larger injections to reduce segment count.
        val buf = ByteArray(16_384)
        try {
            while (alive.get()) {
                val n = inp?.read(buf) ?: -1
                if (n < 0) break
                if (n == 0) continue
                val payload = buf.copyOf(n)
                onStreamData(this, payload, false)
                val pkt = PacketUtils.buildIpv4Tcp(
                    srcIp = serverIp,
                    dstIp = clientIp,
                    srcPort = serverPort,
                    dstPort = clientPort,
                    seq = serverSeq,
                    ack = clientSeq,
                    flags = PacketUtils.PSH or PacketUtils.ACK,
                    payload = payload,
                    window = advertisedWindow()
                )
                writeToTun(pkt)
                serverSeq = (serverSeq + n) and 0xFFFFFFFFL
            }
        } catch (e: Exception) {
            if (alive.get()) Log.d(tag, "Remote read ended: ${e.message}")
        } finally {
            try {
                sendAck(PacketUtils.FIN or PacketUtils.ACK)
            } catch (_: Exception) {}
            close()
        }
    }

    private fun sendRst() {
        try {
            val rst = PacketUtils.buildIpv4Tcp(
                srcIp = serverIp,
                dstIp = clientIp,
                srcPort = serverPort,
                dstPort = clientPort,
                seq = serverSeq,
                ack = clientSeq,
                flags = PacketUtils.RST or PacketUtils.ACK
            )
            writeToTun(rst)
        } catch (_: Exception) {}
    }

    fun close() {
        if (!alive.compareAndSet(true, false)) return
        reorder.clear()
        reorderBytes = 0
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        onClosed(key)
    }

    // --- Modular 32-bit sequence helpers (RFC 793) ---

    /** True if a is strictly before b in the sequence space. */
    private fun seqLt(a: Long, b: Long): Boolean {
        val diff = (a - b) and 0xFFFFFFFFL
        return diff > 0x80000000L
    }

    /** True if a is before or equal to b. */
    private fun seqLe(a: Long, b: Long): Boolean = a == b || seqLt(a, b)

    /** Bytes from a to b (b - a), modular. */
    private fun seqDelta(b: Long, a: Long): Long = (b - a) and 0xFFFFFFFFL
}
