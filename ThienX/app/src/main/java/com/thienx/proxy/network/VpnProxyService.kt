package com.thienx.proxy.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.thienx.proxy.R
import com.thienx.proxy.engine.AimEngine
import com.thienx.proxy.engine.PacketParser
import com.thienx.proxy.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class VpnProxyService : VpnService() {

    companion object {
        const val ACTION_START = "com.thienx.proxy.START"
        const val ACTION_STOP  = "com.thienx.proxy.STOP"
        const val CHANNEL_ID   = "thienx_vpn"
        const val NOTIF_ID     = 1

        // Free Fire server IP ranges (Garena)
        val FF_SERVER_RANGES = listOf(
            "103.28.54.",
            "103.28.55.",
            "120.253.",
            "49.236.",
            "103.252.",
        )
        // FF UDP ports
        val FF_PORTS = setOf(10012, 10013, 10014, 10100, 10200, 10201)

        @Volatile var isRunning = false
        @Volatile var packetsProcessed = 0L
        @Volatile var packetsModified  = 0L
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var aimEngine: AimEngine

    // ─── Lifecycle ─────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        aimEngine = AimEngine()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> { start(); START_STICKY }
            ACTION_STOP  -> { stop(); START_NOT_STICKY }
            else -> START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    // ─── Start / Stop ───────────────────────────────────────────────
    private fun start() {
        if (isRunning) return
        startForeground(NOTIF_ID, buildNotification("Đang chạy…"))

        vpnInterface = buildVpnInterface()
        if (vpnInterface == null) {
            stopSelf()
            return
        }

        isRunning = true
        packetsProcessed = 0
        packetsModified  = 0

        scope.launch { runProxyLoop() }
    }

    private fun stop() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    // ─── VPN Interface ──────────────────────────────────────────────
    private fun buildVpnInterface(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("Thiên X Proxy")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            null
        }
    }

    // ─── Main Proxy Loop ────────────────────────────────────────────
    private suspend fun runProxyLoop() = withContext(Dispatchers.IO) {
        val tunFd = vpnInterface ?: return@withContext
        val input  = FileInputStream(tunFd.fileDescriptor)
        val output = FileOutputStream(tunFd.fileDescriptor)

        val buffer = ByteBuffer.allocate(32767)

        while (isRunning) {
            try {
                buffer.clear()
                val len = input.read(buffer.array())
                if (len <= 0) continue

                buffer.limit(len)
                packetsProcessed++

                val packet = PacketParser.parse(buffer.array(), len)
                if (packet == null) {
                    // Pass through
                    output.write(buffer.array(), 0, len)
                    continue
                }

                // Check if FF packet
                if (isFreefirePacket(packet)) {
                    val modified = aimEngine.processPacket(packet)
                    if (modified != null) {
                        packetsModified++
                        output.write(modified.rawBytes)
                    } else {
                        output.write(buffer.array(), 0, len)
                    }
                } else {
                    // Forward non-FF packets
                    forwardPacket(packet, output)
                }
            } catch (e: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun isFreefirePacket(packet: ParsedPacket): Boolean {
        // Check by destination port
        if (packet.destPort in FF_PORTS) return true
        // Check by server IP range
        val destIp = packet.destIp
        return FF_SERVER_RANGES.any { destIp.startsWith(it) }
    }

    private fun forwardPacket(packet: ParsedPacket, output: FileOutputStream) {
        try {
            val socket = DatagramSocket()
            protect(socket)
            val data = DatagramPacket(
                packet.payload,
                packet.payload.size,
                InetAddress.getByName(packet.destIp),
                packet.destPort
            )
            socket.send(data)
            socket.close()
            output.write(packet.rawBytes)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ─── Notification ───────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Thiên X VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Proxy aim service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("⚡ THIÊN X")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("⚡ THIÊN X")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }
}

// Data class cho parsed packet
data class ParsedPacket(
    val version: Int,
    val protocol: Int,  // 17 = UDP
    val srcIp: String,
    val destIp: String,
    val srcPort: Int,
    val destPort: Int,
    val payload: ByteArray,
    val rawBytes: ByteArray,
    var modified: Boolean = false
)
