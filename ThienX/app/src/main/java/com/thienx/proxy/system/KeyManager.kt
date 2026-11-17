package com.thienx.proxy.system

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KeyManager {

    // ── Đổi thành domain của mày ──────────────────────────────────
    private const val KEY_API = "https://api.thienx.vn/v1/key/verify"
    // ──────────────────────────────────────────────────────────────

    private const val PREF = "key_store"

    data class KeyInfo(
        val key: String,
        val username: String,
        val expireTs: Long,
        val tier: String,     // "free" | "pro" | "vip"
        val serverIp: String,
    )

    var currentKey: KeyInfo? = null
        private set

    fun isKeyValid(ctx: Context): Boolean {
        load(ctx)
        val k = currentKey ?: return false
        return System.currentTimeMillis() < k.expireTs
    }

    fun getExpireString(): String {
        val ts = currentKey?.expireTs ?: return "—"
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(ts))
    }

    fun getRemainingHours(): Long {
        val ts = currentKey?.expireTs ?: return 0
        return (ts - System.currentTimeMillis()) / 3_600_000
    }

    // ─── Verify key với server ──────────────────────────────────────
    suspend fun verify(ctx: Context, key: String): Result<KeyInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL(KEY_API)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "ThienX/1.0")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000

            val body = JSONObject().apply {
                put("key", key)
                put("device", getDeviceId(ctx))
                put("app", "thienx")
            }.toString()

            conn.outputStream.write(body.toByteArray())
            conn.outputStream.flush()

            val code = conn.responseCode
            if (code != 200) {
                return@withContext Result.failure(Exception("Server lỗi: $code"))
            }

            val resp = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(resp)

            if (!json.getBoolean("success")) {
                return@withContext Result.failure(Exception(json.optString("message", "Key không hợp lệ")))
            }

            val info = KeyInfo(
                key       = key,
                username  = json.optString("username", "User"),
                expireTs  = json.optLong("expire_ts", 0L),
                tier      = json.optString("tier", "free"),
                serverIp  = json.optString("server_ip", ""),
            )

            currentKey = info
            save(ctx, info)
            Result.success(info)

        } catch (e: Exception) {
            // Offline fallback: check local cache
            val cached = load(ctx)
            if (cached != null && System.currentTimeMillis() < cached.expireTs) {
                currentKey = cached
                Result.success(cached)
            } else {
                Result.failure(Exception("Không kết nối được server: ${e.message}"))
            }
        }
    }

    // ─── Lưu / đọc ─────────────────────────────────────────────────
    private fun save(ctx: Context, info: KeyInfo) {
        ctx.getSharedPreferences(PREF, 0).edit().apply {
            putString("key",       info.key)
            putString("username",  info.username)
            putLong("expire_ts",   info.expireTs)
            putString("tier",      info.tier)
            putString("server_ip", info.serverIp)
            apply()
        }
    }

    fun load(ctx: Context): KeyInfo? {
        val p = ctx.getSharedPreferences(PREF, 0)
        val key = p.getString("key", null) ?: return null
        val info = KeyInfo(
            key       = key,
            username  = p.getString("username", "User") ?: "User",
            expireTs  = p.getLong("expire_ts", 0L),
            tier      = p.getString("tier", "free") ?: "free",
            serverIp  = p.getString("server_ip", "") ?: "",
        )
        currentKey = info
        return info
    }

    fun logout(ctx: Context) {
        currentKey = null
        ctx.getSharedPreferences(PREF, 0).edit().clear().apply()
    }

    private fun getDeviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences("device", 0)
        var id = p.getString("id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            p.edit().putString("id", id).apply()
        }
        return id
    }
}

// ─── GameConfig ─────────────────────────────────────────────────
object GameConfig {

    private const val CONFIG_API = "https://api.thienx.vn/v1/config"

    data class ServerConfig(
        val ffPorts: List<Int>,
        val ffIpRanges: List<String>,
        val aimPacketSignature: ByteArray?,
        val enabled: Boolean,
        val message: String,
    )

    var current: ServerConfig? = null
        private set

    fun refresh(ctx: android.content.Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url  = URL(CONFIG_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer ${KeyManager.currentKey?.key}")
                conn.connectTimeout = 8_000
                conn.readTimeout    = 8_000

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    parse(resp)
                }
            } catch (e: Exception) {
                // Use defaults
            }
        }
    }

    private fun parse(json: String) {
        try {
            val obj = JSONObject(json)
            val ports    = obj.optJSONArray("ff_ports")
            val ranges   = obj.optJSONArray("ff_ip_ranges")
            val portList = mutableListOf<Int>()
            val rangeList= mutableListOf<String>()

            ports?.let { for (i in 0 until it.length()) portList.add(it.getInt(i)) }
            ranges?.let { for (i in 0 until it.length()) rangeList.add(it.getString(i)) }

            current = ServerConfig(
                ffPorts             = portList.ifEmpty { listOf(10012, 10013, 10014) },
                ffIpRanges          = rangeList,
                aimPacketSignature  = null,
                enabled             = obj.optBoolean("enabled", true),
                message             = obj.optString("message", ""),
            )
        } catch (e: Exception) {
            // Keep defaults
        }
    }
}

// ─── Boot Receiver ───────────────────────────────────────────────
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
        // Auto-start không cần thiết — user mở app thủ công
    }
}
