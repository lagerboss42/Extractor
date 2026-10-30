package com.majk.extractor.vpn

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One outbound TCP connection: app (via TUN) ↔ remote host (real socket).
 * Buffers both directions so HTTP bodies can be reassembled across segments.
 */
class TcpSession(
    private val vpn: VpnService,
    private val clientIp: ByteArray,
    private val clientPort: Int,
    private val serverIp: ByteArray,
    private val serverPort: Int,
    private val clientIsn: Long,
    private val writeToTun: (ByteArray) -> Unit,
    private val onStreamData: (session: TcpSession, data: ByteArray, fromClient: Boolean) -> Unit
) {
    private val tag = "TcpSession"
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var inp: InputStream? = null

    private var serverSeq: Long = Random.nextInt().toLong() and 0xFFFFFFFFL
    private var clientSeq: Long = (clientIsn + 1) and 0xFFFFFFFFL

    private val alive = AtomicBoolean(true)
    private var readerThread: Thread? = null

    /** Accumulated client→server and server→client bytes (for HTTP reassembly). */
    val clientBuffer = StringBuilder()
    val serverBuffer = StringBuilder()

    val remotePort: Int get() = serverPort
    val localPort: Int get() = clientPort

    val key: String
        get() = "${PacketUtils.ipToString(clientIp, 0)}:$clientPort-" +
            "${PacketUtils.ipToString(serverIp, 0)}:$serverPort"

    val involvesPort81: Boolean
        get() = serverPort == 81 || clientPort == 81

    fun acceptSyn() {
        try {
            val s = Socket()
            vpn.protect(s)
            s.tcpNoDelay = true
            s.soTimeout = 0
            s.connect(
                InetSocketAddress(PacketUtils.ipToString(serverIp, 0), serverPort),
                12_000
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
                flags = PacketUtils.SYN or PacketUtils.ACK
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
                out?.write(payload)
                out?.flush()
                onStreamData(this, payload, true)
                clientSeq = (seq + payload.size) and 0xFFFFFFFFL
                val ackPkt = PacketUtils.buildIpv4Tcp(
                    srcIp = serverIp,
                    dstIp = clientIp,
                    srcPort = serverPort,
                    dstPort = clientPort,
                    seq = serverSeq,
                    ack = clientSeq,
                    flags = PacketUtils.ACK
                )
                writeToTun(ackPkt)
            } catch (e: Exception) {
                Log.w(tag, "Write to remote failed: ${e.message}")
                close()
            }
        }

        if (flags and PacketUtils.FIN != 0) {
            clientSeq = (clientSeq + 1) and 0xFFFFFFFFL
            try {
                socket?.shutdownOutput()
            } catch (_: Exception) {}
            val finAck = PacketUtils.buildIpv4Tcp(
                srcIp = serverIp,
                dstIp = clientIp,
                srcPort = serverPort,
                dstPort = clientPort,
                seq = serverSeq,
                ack = clientSeq,
                flags = PacketUtils.FIN or PacketUtils.ACK
            )
            writeToTun(finAck)
            serverSeq = (serverSeq + 1) and 0xFFFFFFFFL
        }
    }

    private fun readRemoteLoop() {
        val buf = ByteArray(32_767)
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
                    payload = payload
                )
                writeToTun(pkt)
                serverSeq = (serverSeq + n) and 0xFFFFFFFFL
            }
        } catch (e: Exception) {
            if (alive.get()) Log.d(tag, "Remote read ended: ${e.message}")
        } finally {
            try {
                val fin = PacketUtils.buildIpv4Tcp(
                    srcIp = serverIp,
                    dstIp = clientIp,
                    srcPort = serverPort,
                    dstPort = clientPort,
                    seq = serverSeq,
                    ack = clientSeq,
                    flags = PacketUtils.FIN or PacketUtils.ACK
                )
                writeToTun(fin)
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
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}
