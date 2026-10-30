package com.majk.extractor.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.majk.extractor.MainActivity
import com.majk.extractor.data.parser.OpterHttpParser
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local VPN with userspace TCP relay:
 *  - Apps talk to the TUN as if talking to the internet
 *  - We open protected sockets to the real hosts and inject replies into the TUN
 *  - TCP port 81 payloads are logged and parsed (Opter Driver)
 */
class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val capturing = AtomicBoolean(false)

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private var tunOutput: FileOutputStream? = null

    private val logFile by lazy {
        File(getExternalFilesDir(null), "opter_traffic_log.txt")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) return

        val builder = Builder()
            .setSession("Extractor")
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .setBlocking(true)

        val opterPackages = listOf("com.opter.driver")
        var restricted = false
        for (pkg in opterPackages) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                builder.addAllowedApplication(pkg)
                restricted = true
                Log.i(TAG, "VPN restricted to $pkg")
            } catch (_: Exception) {
            }
        }
        if (!restricted) {
            Log.w(TAG, "Opter package not found – full-tunnel capture")
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}
        }

        try {
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            stopSelf()
            return
        }

        if (vpnInterface == null) {
            stopSelf()
            return
        }

        CaptureRepository.clear()
        try {
            FileWriter(logFile, false).use { it.write("=== Extractor Traffic Log ===\n") }
        } catch (_: Exception) {}

        capturing.set(true)
        VpnStatus.isRunning = true
        startForeground(NOTIFICATION_ID, createNotification("Capturing… 0 stops"))

        captureJob = scope.launch {
            runCaptureLoop(vpnInterface!!)
        }
        Log.i(TAG, "VPN started")
    }

    private fun runCaptureLoop(vpn: ParcelFileDescriptor) {
        val input = FileInputStream(vpn.fileDescriptor)
        val output = FileOutputStream(vpn.fileDescriptor)
        tunOutput = output
        val packet = ByteArray(32767)

        try {
            while (capturing.get()) {
                val length = try {
                    input.read(packet)
                } catch (e: Exception) {
                    if (capturing.get()) Log.e(TAG, "TUN read: ${e.message}")
                    break
                }
                if (length <= 0) continue
                try {
                    handlePacket(packet, length)
                } catch (e: Exception) {
                    Log.w(TAG, "handlePacket: ${e.message}")
                }
            }
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            tunOutput = null
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }

    private fun writeToTun(data: ByteArray) {
        try {
            synchronized(this) {
                tunOutput?.write(data)
                tunOutput?.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "TUN write: ${e.message}")
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int) {
        if (length < 1) return
        val vi = packet[0].toInt() and 0xFF
        val version = vi shr 4
        if (version == 6) {
            if (length >= 40) {
                try {
                    FileWriter(logFile, true).use {
                        it.append("IPv6 packet len=$length (not relayed – Opter may need IPv4)\n")
                    }
                } catch (_: Exception) {}
            }
            return
        }
        if (version != 4) return
        val ihl = (vi and 0x0F) * 4
        if (length < ihl + 8) return

        val protocol = packet[9].toInt() and 0xFF
        when (protocol) {
            6 -> handleTcp(packet, length, ihl)
            17 -> handleUdp(packet, length, ihl)
        }
    }

    private fun handleTcp(packet: ByteArray, length: Int, ihl: Int) {
        if (length < ihl + 20) return

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = PacketUtils.readU16(packet, ihl)
        val dstPort = PacketUtils.readU16(packet, ihl + 2)
        val seq = PacketUtils.readU32(packet, ihl + 4)
        val ack = PacketUtils.readU32(packet, ihl + 8)
        val dataOffset = ((packet[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val flags = packet[ihl + 13].toInt() and 0xFF
        val payloadOff = ihl + dataOffset
        val payload = if (length > payloadOff) packet.copyOfRange(payloadOff, length) else ByteArray(0)

        val key = "${PacketUtils.ipToString(srcIp, 0)}:$srcPort-${PacketUtils.ipToString(dstIp, 0)}:$dstPort"

        if (flags and PacketUtils.SYN != 0 && flags and PacketUtils.ACK == 0) {
            sessions.remove(key)?.close()
            val session = TcpSession(
                vpn = this,
                clientIp = srcIp,
                clientPort = srcPort,
                serverIp = dstIp,
                serverPort = dstPort,
                clientIsn = seq,
                writeToTun = { writeToTun(it) },
                onStreamData = { sess, data, fromClient -> onStreamData(sess, data, fromClient) }
            )
            sessions[key] = session
            session.acceptSyn()
            try {
                FileWriter(logFile, true).use {
                    it.append("TCP SYN ${PacketUtils.ipToString(srcIp, 0)}:$srcPort → ${PacketUtils.ipToString(dstIp, 0)}:$dstPort\n")
                }
            } catch (_: Exception) {}
            return
        }

        val session = sessions[key]
        if (session != null) {
            session.onClientSegment(seq, ack, flags, payload)
        }
    }

    private fun onStreamData(session: TcpSession, data: ByteArray, fromClient: Boolean) {
        if (data.isEmpty()) return
        val text = String(data, Charsets.ISO_8859_1)

        if (fromClient) session.clientBuffer.append(text) else session.serverBuffer.append(text)

        val looksLikeOpter = session.involvesPort81 ||
            text.contains("DEL\t") ||
            text.contains("PSS\t") ||
            text.contains("Bekr", ignoreCase = true) ||
            text.contains("Lastad", ignoreCase = true) ||
            text.contains("LJUNGSBRO", ignoreCase = true) ||
            text.contains("Alltransport", ignoreCase = true) ||
            text.contains("Köpetorp", ignoreCase = true) ||
            text.contains("Kopetorp", ignoreCase = true)

        if (!looksLikeOpter) return

        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val direction = when {
            fromClient && session.remotePort == 81 -> "→ REQUEST to :81"
            !fromClient && session.remotePort == 81 -> "← RESPONSE from :81"
            fromClient -> "→ client→server :${session.remotePort}"
            else -> "← server→client :${session.remotePort}"
        }

        val entry = buildString {
            appendLine("--------------------------------------------------")
            appendLine("[$timestamp] $direction  (${data.size} bytes)")
            appendLine(text)
            appendLine()
        }
        try {
            FileWriter(logFile, true).use { it.append(entry) }
        } catch (_: Exception) {}

        parseAndStore(text)
        if (session.serverBuffer.length in 1..500_000) {
            parseAndStore(session.serverBuffer.toString())
        }
        if (session.clientBuffer.length in 1..500_000) {
            parseAndStore(session.clientBuffer.toString())
        }
    }

    private fun parseAndStore(text: String) {
        if (text.isBlank()) return
        try {
            val found = OpterHttpParser.parse(text)
            if (found.isNotEmpty()) {
                CaptureRepository.addAll(found)
                updateNotification()
                Log.i(TAG, "Parsed ${found.size} stops (total ${CaptureRepository.lastParseCount})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse: ${e.message}")
        }
    }

    private fun handleUdp(packet: ByteArray, length: Int, ihl: Int) {
        if (length < ihl + 8) return
        val srcPort = PacketUtils.readU16(packet, ihl)
        val dstPort = PacketUtils.readU16(packet, ihl + 2)
        if (dstPort != 53) return

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val payloadOff = ihl + 8
        val payload = packet.copyOfRange(payloadOff, length)

        scope.launch {
            try {
                val ds = DatagramSocket()
                protect(ds)
                ds.soTimeout = 4000
                val dest = InetAddress.getByAddress(dstIp)
                ds.send(DatagramPacket(payload, payload.size, dest, 53))
                val buf = ByteArray(1024)
                val recv = DatagramPacket(buf, buf.size)
                ds.receive(recv)
                ds.close()

                val udpLen = 8 + recv.length
                val total = 20 + udpLen
                val out = ByteArray(total)
                out[0] = 0x45.toByte()
                PacketUtils.writeU16(out, 2, total)
                PacketUtils.writeU16(out, 4, (System.currentTimeMillis() and 0xFFFF).toInt())
                out[8] = 64
                out[9] = 17
                System.arraycopy(dstIp, 0, out, 12, 4)
                System.arraycopy(srcIp, 0, out, 16, 4)
                PacketUtils.writeU16(out, 10, PacketUtils.checksum(out, 0, 20))

                PacketUtils.writeU16(out, 20, 53)
                PacketUtils.writeU16(out, 22, srcPort)
                PacketUtils.writeU16(out, 24, udpLen)
                PacketUtils.writeU16(out, 26, 0)
                System.arraycopy(recv.data, recv.offset, out, 28, recv.length)
                writeToTun(out)
            } catch (e: Exception) {
                Log.w(TAG, "DNS: ${e.message}")
            }
        }
    }

    private fun updateNotification() {
        val count = CaptureRepository.lastParseCount
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification("Capturing… $count stops found"))
    }

    private fun stopVpn() {
        capturing.set(false)
        captureJob?.cancel()
        captureJob = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        VpnStatus.isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped. Stops: ${CaptureRepository.lastParseCount}")
    }

    private fun createNotification(content: String): Notification {
        val channelId = "opter_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Extractor", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Extractor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.majk.extractor.START_VPN"
        const val ACTION_STOP = "com.majk.extractor.STOP_VPN"
        private const val TAG = "LocalVpnService"
        private const val NOTIFICATION_ID = 1001
    }
}
