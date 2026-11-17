package com.thienx.proxy.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.thienx.proxy.system.KeyManager
import com.thienx.proxy.network.VpnProxyService

class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF0A0A1E.toInt())
        }

        val info = KeyManager.currentKey

        fun tv(text: String, color: Int = 0xFFDDDDEE.toInt(), size: Float = 14f) =
            TextView(requireContext()).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setPadding(0, 8, 0, 8)
            }

        layout.addView(tv("⚡ THIÊN X", 0xFF7878FF.toInt(), 22f))
        layout.addView(tv("────────────────────────", 0xFF333355.toInt(), 10f))

        if (info != null) {
            layout.addView(tv("User: ${info.username}"))
            layout.addView(tv("Tier: ${info.tier.uppercase()}", when(info.tier) {
                "vip" -> 0xFFFFD700.toInt()
                "pro" -> 0xFF7878FF.toInt()
                else  -> 0xFF888888.toInt()
            }))
            layout.addView(tv("Hết hạn: ${KeyManager.getExpireString()}"))
            layout.addView(tv("Còn lại: ${KeyManager.getRemainingHours()}h"))
        } else {
            layout.addView(tv("Chưa đăng nhập", 0xFF888888.toInt()))
        }

        layout.addView(tv("────────────────────────", 0xFF333355.toInt(), 10f))
        layout.addView(tv("Packets xử lý: ${VpnProxyService.packetsProcessed}"))
        layout.addView(tv("Packets chỉnh: ${VpnProxyService.packetsModified}"))
        layout.addView(tv("Proxy: ${if (VpnProxyService.isRunning) "Đang chạy" else "Dừng"}",
            if (VpnProxyService.isRunning) 0xFF00E676.toInt() else 0xFF888888.toInt()
        ))

        layout.addView(tv("────────────────────────", 0xFF333355.toInt(), 10f))
        layout.addView(tv("Version: 1.0.0 | by Thiên X"))
        layout.addView(tv("Telegram: @thienx_vn", 0xFF7878FF.toInt()))

        return layout
    }
}

class NotificationFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return TextView(requireContext()).apply {
            text = "Không có thông báo mới"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = android.view.Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
}
