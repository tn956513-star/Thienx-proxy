package com.thienx.proxy.engine

import com.thienx.proxy.network.ParsedPacket
import kotlin.math.*

class AimEngine {

    // Bone vertical offsets (degrees, positive = up)
    private val BONE_OFFSETS = mapOf(
        AimConfig.BonePart.HEAD  to -18f,  // Aim lên đầu
        AimConfig.BonePart.NECK  to -10f,  // Cổ
        AimConfig.BonePart.CHEST to   0f,  // Ngực — baseline
    )

    // Aim lag ghost state
    private var lastPitch = 0f
    private var lastYaw   = 0f
    private var ghostActive = false

    // ─── Main Entry ─────────────────────────────────────────────────
    fun processPacket(packet: ParsedPacket): ParsedPacket? {
        if (!AimConfig.aimEnabled) return null

        val payload = packet.payload.copyOf()

        // Tìm aim vector trong payload
        val aimOffset = PacketParser.findAimVectorOffset(payload)
        if (aimOffset < 0) return null

        var pitch = PacketParser.readFloat(payload, aimOffset)
        var yaw   = PacketParser.readFloat(payload, aimOffset + 4)

        val originalPitch = pitch
        val originalYaw   = yaw

        // Apply bone targeting
        pitch = applyBoneOffset(pitch)

        // Apply strength (lerp từ original → target)
        val strength = getActiveStrength() / 100f
        pitch = lerp(originalPitch, pitch, strength)

        // Aim lag / Ghost
        if (AimConfig.aimLagEnabled) {
            pitch = applyAimLag(pitch, originalPitch)
            yaw   = applyAimLag(yaw, originalYaw, isYaw = true)
        }

        if (AimConfig.ghostEnabled) {
            pitch = applyGhost(pitch)
        }

        // Clamp
        pitch = pitch.coerceIn(-89f, 89f)
        yaw   = yaw.coerceIn(-180f, 180f)

        // Write back
        PacketParser.writeFloat(payload, aimOffset,     pitch)
        PacketParser.writeFloat(payload, aimOffset + 4, yaw)

        // Fix dame: apply small perturbation to avoid perfect shot detection
        if (AimConfig.fixDameEnabled) {
            applyDameFix(payload, aimOffset)
        }

        // Rebuild packet
        val newRaw = PacketParser.rebuild(packet, payload)
        return packet.copy(
            payload  = payload,
            rawBytes = newRaw,
            modified = true
        )
    }

    // ─── Bone Offset ────────────────────────────────────────────────
    private fun applyBoneOffset(pitch: Float): Float {
        val offset = BONE_OFFSETS[AimConfig.targetBone] ?: 0f
        return pitch + offset
    }

    // ─── Strength ───────────────────────────────────────────────────
    private fun getActiveStrength(): Int = when (AimConfig.targetBone) {
        AimConfig.BonePart.HEAD  -> AimConfig.headStrength
        AimConfig.BonePart.NECK  -> AimConfig.neckStrength
        AimConfig.BonePart.CHEST -> AimConfig.chestStrength
    }

    // ─── Aim Lag ────────────────────────────────────────────────────
    /**
     * Aim lag: thêm độ trễ mượt vào aim — trông tự nhiên hơn, tránh detect.
     * Smooth factor: 0.3 = rất chậm, 0.9 = gần instant
     */
    private fun applyAimLag(
        current: Float, original: Float,
        smoothFactor: Float = 0.65f,
        isYaw: Boolean = false
    ): Float {
        val last = if (isYaw) lastYaw else lastPitch
        val smoothed = lerp(last, current, smoothFactor)
        if (isYaw) lastYaw = smoothed else lastPitch = smoothed
        return smoothed
    }

    // ─── Ghost ──────────────────────────────────────────────────────
    /**
     * Ghost: oscillate aim nhẹ xung quanh target — tránh lock-on detection.
     * Amplitude nhỏ (~0.5°), frequency ngẫu nhiên.
     */
    private var ghostPhase = 0.0
    private fun applyGhost(pitch: Float): Float {
        ghostPhase += 0.15 + Math.random() * 0.05
        val ghostOffset = (sin(ghostPhase) * 0.4f).toFloat()
        return pitch + ghostOffset
    }

    // ─── Fix Dame ───────────────────────────────────────────────────
    /**
     * Fix dame súng: micro-adjust aim để compensate cho recoil của
     * một số súng tiểu liên (submachine guns).
     * Chỉ apply perturbation nhỏ ±0.01° để không lộ.
     */
    private fun applyDameFix(payload: ByteArray, aimOffset: Int) {
        val pitch = PacketParser.readFloat(payload, aimOffset)
        val jitter = (Math.random() * 0.02 - 0.01).toFloat()
        PacketParser.writeFloat(payload, aimOffset, pitch + jitter)
    }

    // ─── Math Helpers ───────────────────────────────────────────────
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
}

// ─── Aim Config singleton ───────────────────────────────────────
object AimConfig {
    enum class BonePart { HEAD, NECK, CHEST }

    var aimEnabled    = true
    var targetBone    = BonePart.CHEST
    var headStrength  = 39
    var neckStrength  = 25
    var chestStrength = 15
    var aimLagEnabled = true
    var ghostEnabled  = false
    var fixDameEnabled = false

    fun reset() {
        headStrength  = 39
        neckStrength  = 25
        chestStrength = 15
        aimLagEnabled = true
        ghostEnabled  = false
        fixDameEnabled = false
        targetBone    = BonePart.CHEST
    }

    fun save(ctx: android.content.Context) {
        val prefs = ctx.getSharedPreferences("aim_cfg", 0).edit()
        prefs.putInt("headStr",    headStrength)
        prefs.putInt("neckStr",    neckStrength)
        prefs.putInt("chestStr",   chestStrength)
        prefs.putBoolean("aimLag", aimLagEnabled)
        prefs.putBoolean("ghost",  ghostEnabled)
        prefs.putBoolean("fixDame",fixDameEnabled)
        prefs.putString("bone",    targetBone.name)
        prefs.apply()
    }

    fun load(ctx: android.content.Context) {
        val prefs = ctx.getSharedPreferences("aim_cfg", 0)
        headStrength   = prefs.getInt("headStr",    39)
        neckStrength   = prefs.getInt("neckStr",    25)
        chestStrength  = prefs.getInt("chestStr",   15)
        aimLagEnabled  = prefs.getBoolean("aimLag", true)
        ghostEnabled   = prefs.getBoolean("ghost",  false)
        fixDameEnabled = prefs.getBoolean("fixDame",false)
        targetBone     = try {
            BonePart.valueOf(prefs.getString("bone", "CHEST") ?: "CHEST")
        } catch (e: Exception) { BonePart.CHEST }
    }
}

// ─── ESP Config singleton ───────────────────────────────────────
object EspConfig {
    var mocoEspEnabled   = false
    var boxEspEnabled    = false
    var healthBarEnabled = false
    var nameEspEnabled   = false
    var distanceEnabled  = false
    var crosshairEnabled = false

    var boxColor    = 0xFF00E676.toInt()
    var nameColor   = 0xFFFFFFFF.toInt()

    fun save(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("esp_cfg", 0).edit()
        p.putBoolean("moco",   mocoEspEnabled)
        p.putBoolean("box",    boxEspEnabled)
        p.putBoolean("health", healthBarEnabled)
        p.putBoolean("name",   nameEspEnabled)
        p.putBoolean("dist",   distanceEnabled)
        p.putBoolean("cross",  crosshairEnabled)
        p.putInt("boxColor",   boxColor)
        p.putInt("nameColor",  nameColor)
        p.apply()
    }

    fun load(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("esp_cfg", 0)
        mocoEspEnabled   = p.getBoolean("moco",   false)
        boxEspEnabled    = p.getBoolean("box",    false)
        healthBarEnabled = p.getBoolean("health", false)
        nameEspEnabled   = p.getBoolean("name",   false)
        distanceEnabled  = p.getBoolean("dist",   false)
        crosshairEnabled = p.getBoolean("cross",  false)
        boxColor         = p.getInt("boxColor",   0xFF00E676.toInt())
        nameColor        = p.getInt("nameColor",  0xFFFFFFFF.toInt())
    }
}
