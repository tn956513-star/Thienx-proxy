package com.thienx.proxy.engine

import com.thienx.proxy.network.ParsedPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketParser {

    /**
     * Parse raw bytes từ TUN interface thành ParsedPacket.
     * Chỉ xử lý IPv4 UDP — pass null cho các loại khác.
     */
    fun parse(raw: ByteArray, len: Int): ParsedPacket? {
        if (len < 28) return null // Min IP(20) + UDP(8)

        val buf = ByteBuffer.wrap(raw, 0, len).order(ByteOrder.BIG_ENDIAN)

        // IP header
        val versionIhl = buf.get(0).toInt() and 0xFF
        val version = versionIhl shr 4
        if (version != 4) return null  // Chỉ IPv4

        val ihl = (versionIhl and 0x0F) * 4
        val protocol = buf.get(9).toInt() and 0xFF
        if (protocol != 17) return null  // Chỉ UDP (17)

        val totalLen = buf.getShort(2).toInt() and 0xFFFF

        // Source IP
        val srcIp = "${buf.get(12).toInt() and 0xFF}.${buf.get(13).toInt() and 0xFF}" +
                    ".${buf.get(14).toInt() and 0xFF}.${buf.get(15).toInt() and 0xFF}"

        // Dest IP
        val dstIp = "${buf.get(16).toInt() and 0xFF}.${buf.get(17).toInt() and 0xFF}" +
                    ".${buf.get(18).toInt() and 0xFF}.${buf.get(19).toInt() and 0xFF}"

        // UDP header (starts at ihl)
        if (len < ihl + 8) return null
        val srcPort  = buf.getShort(ihl).toInt() and 0xFFFF
        val dstPort  = buf.getShort(ihl + 2).toInt() and 0xFFFF
        val udpLen   = buf.getShort(ihl + 4).toInt() and 0xFFFF

        // Payload
        val payloadOff = ihl + 8
        val payloadLen = udpLen - 8
        if (payloadLen <= 0 || payloadOff + payloadLen > len) return null
        val payload = raw.copyOfRange(payloadOff, payloadOff + payloadLen)

        return ParsedPacket(
            version  = version,
            protocol = protocol,
            srcIp    = srcIp,
            destIp   = dstIp,
            srcPort  = srcPort,
            destPort = dstPort,
            payload  = payload,
            rawBytes = raw.copyOf(len)
        )
    }

    /**
     * Rebuild raw packet từ ParsedPacket sau khi modify payload.
     * Recompute UDP checksum.
     */
    fun rebuild(packet: ParsedPacket, newPayload: ByteArray): ByteArray {
        val ihl = 20 // Standard IP header
        val udpLen = 8 + newPayload.size
        val totalLen = ihl + udpLen

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // Copy original IP header (first 20 bytes)
        buf.put(packet.rawBytes, 0, ihl)
        // Update total length
        buf.putShort(2, totalLen.toShort())
        // Clear IP checksum (will not recompute for performance; kernel ignores)
        buf.putShort(10, 0)

        // Parse IPs for checksum calculation
        val srcParts = packet.srcIp.split(".")
        val dstParts = packet.destIp.split(".")

        // UDP header
        buf.putShort(ihl + 0, packet.srcPort.toShort())
        buf.putShort(ihl + 2, packet.destPort.toShort())
        buf.putShort(ihl + 4, udpLen.toShort())
        buf.putShort(ihl + 6, 0) // Checksum placeholder

        // Payload
        buf.put(newPayload)

        // Compute UDP checksum
        val checksum = computeUdpChecksum(buf.array(), ihl, udpLen, srcParts, dstParts)
        buf.putShort(ihl + 6, checksum.toShort())

        return buf.array()
    }

    private fun computeUdpChecksum(
        data: ByteArray, udpOff: Int, udpLen: Int,
        src: List<String>, dst: List<String>
    ): Int {
        // Pseudo header + UDP
        var sum = 0L

        // Source IP
        sum += ((src[0].toInt() shl 8) or src[1].toInt()).toLong()
        sum += ((src[2].toInt() shl 8) or src[3].toInt()).toLong()
        // Dest IP
        sum += ((dst[0].toInt() shl 8) or dst[1].toInt()).toLong()
        sum += ((dst[2].toInt() shl 8) or dst[3].toInt()).toLong()
        // Protocol + length
        sum += 17L
        sum += udpLen.toLong()

        // UDP data
        var i = udpOff
        while (i < udpOff + udpLen - 1) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if ((udpLen % 2) == 1) {
            sum += ((data[udpOff + udpLen - 1].toInt() and 0xFF) shl 8).toLong()
        }

        // Fold
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    // ─── Aim Vector Helpers ─────────────────────────────────────────

    /**
     * Tìm aim vector (pitch/yaw) trong payload.
     * FF sử dụng float little-endian cho góc xoay.
     * Heuristic: tìm 2 float liên tiếp trong range [-180, 180]
     */
    fun findAimVectorOffset(payload: ByteArray): Int {
        if (payload.size < 8) return -1
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0..payload.size - 8) {
            try {
                val f1 = buf.getFloat(i)
                val f2 = buf.getFloat(i + 4)
                // Pitch/Yaw range check
                if (f1 in -90f..90f && f2 in -180f..180f) {
                    // Additional sanity: not zero, not NaN
                    if (!f1.isNaN() && !f2.isNaN() && (f1 != 0f || f2 != 0f)) {
                        return i
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }
        return -1
    }

    fun readFloat(payload: ByteArray, offset: Int): Float {
        return ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }

    fun writeFloat(payload: ByteArray, offset: Int, value: Float) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        System.arraycopy(bytes, 0, payload, offset, 4)
    }
}
