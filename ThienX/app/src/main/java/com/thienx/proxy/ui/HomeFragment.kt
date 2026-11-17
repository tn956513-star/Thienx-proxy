package com.thienx.proxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.thienx.proxy.R
import com.thienx.proxy.databinding.FragmentHomeBinding
import com.thienx.proxy.network.VpnProxyService
import com.thienx.proxy.system.GameConfig
import com.thienx.proxy.system.KeyManager
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupKeySection()
        setupGameConfig()
        setupServerStatus()
        setupProxyToggle()
        updateKeyUI()
    }

    // ─── Key Section ─────────────────────────────────────────────────
    private fun setupKeySection() {
        // Nếu đã có key hợp lệ
        val saved = KeyManager.load(requireContext())
        if (saved != null && System.currentTimeMillis() < saved.expireTs) {
            showKeyValid()
        }

        binding.btnGetKey.setOnClickListener {
            // Mở trang lấy key
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://thienx.vn/getkey")  // Đổi domain
            )
            startActivity(intent)
        }

        binding.btnActivate.setOnClickListener {
            val key = binding.etKey.text.toString().trim()
            if (key.isEmpty()) { showToast("Nhập key vào"); return@setOnClickListener }

            binding.btnActivate.isEnabled = false
            binding.tvKeyStatus.text = "Đang xác minh…"

            lifecycleScope.launch {
                val result = KeyManager.verify(requireContext(), key)
                result.fold(
                    onSuccess = {
                        showKeyValid()
                        showToast("Kích hoạt thành công! Chào ${it.username}")
                        GameConfig.refresh(requireContext())
                    },
                    onFailure = {
                        binding.tvKeyStatus.text = "❌ ${it.message}"
                        binding.tvKeyStatus.setTextColor(0xFFFF5555.toInt())
                    }
                )
                binding.btnActivate.isEnabled = true
            }
        }

        binding.btnLogout.setOnClickListener {
            KeyManager.logout(requireContext())
            showKeyInvalid()
        }
    }

    private fun showKeyValid() {
        val info = KeyManager.currentKey ?: return
        binding.layoutKeyInput.visibility  = View.GONE
        binding.layoutKeyValid.visibility  = View.VISIBLE
        binding.tvUsername.text   = info.username
        binding.tvKeyTier.text    = info.tier.uppercase()
        binding.tvExpire.text     = "Hết hạn: ${KeyManager.getExpireString()}"
        binding.tvTimeLeft.text   = "${KeyManager.getRemainingHours()}h còn lại"

        // Tier color
        val tierColor = when (info.tier) {
            "vip"  -> 0xFFFFD700.toInt()
            "pro"  -> 0xFF7878FF.toInt()
            else   -> 0xFF888888.toInt()
        }
        binding.tvKeyTier.setTextColor(tierColor)
    }

    private fun showKeyInvalid() {
        binding.layoutKeyInput.visibility  = View.VISIBLE
        binding.layoutKeyValid.visibility  = View.GONE
        binding.tvKeyStatus.text = "Chưa kích hoạt"
        binding.tvKeyStatus.setTextColor(0xFF888888.toInt())
        binding.etKey.setText("")
    }

    private fun updateKeyUI() {
        if (KeyManager.isKeyValid(requireContext())) showKeyValid()
        else showKeyInvalid()
    }

    // ─── Game Config ─────────────────────────────────────────────────
    private fun setupGameConfig() {
        // Show Free Fire TH (detected package)
        binding.tvGameName.text    = "FREE FIRE TH"
        binding.tvGamePkg.text     = "com.dts.freefireth"
        binding.tvGameStatus.text  = if (isGameInstalled("com.dts.freefireth"))
            "Đã cài đặt" else "Chưa cài đặt"

        // Also check MAX
        val maxInstalled = isGameInstalled("com.dts.freefiremax")
        if (maxInstalled) {
            binding.tvGameName.text = "FREE FIRE MAX"
            binding.tvGamePkg.text  = "com.dts.freefiremax"
            binding.tvGameStatus.text = "Đã cài đặt"
        }

        binding.btnAddGame.setOnClickListener {
            showToast("Tự động detect Free Fire")
        }
    }

    private fun isGameInstalled(pkg: String): Boolean {
        return try {
            requireContext().packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) { false }
    }

    // ─── Server Status ───────────────────────────────────────────────
    private fun setupServerStatus() {
        binding.tvServerStatus.text  = "0 ONLINE"
        binding.tvServerConfig.text  = "Chưa có cấu hình"

        // Poll server count
        lifecycleScope.launch {
            try {
                // TODO: fetch from your API
                kotlinx.coroutines.delay(1000)
                // Placeholder
                binding.tvServerStatus.text = "SERVER"
            } catch (e: Exception) {}
        }
    }

    // ─── Proxy Toggle ────────────────────────────────────────────────
    private fun setupProxyToggle() {
        binding.switchProxy.isChecked = VpnProxyService.isRunning

        binding.switchProxy.setOnCheckedChangeListener { _, checked ->
            val activity = requireActivity() as MainActivity
            if (checked) {
                if (!KeyManager.isKeyValid(requireContext())) {
                    binding.switchProxy.isChecked = false
                    showToast("Cần key hợp lệ")
                    return@setOnCheckedChangeListener
                }
                activity.startProxy()
                binding.tvProxyStatus.text = "Đang chạy"
                binding.tvProxyStatus.setTextColor(0xFF00E676.toInt())
            } else {
                activity.stopProxy()
                binding.tvProxyStatus.text = "Đã dừng"
                binding.tvProxyStatus.setTextColor(0xFF888888.toInt())
            }
        }

        // Stats update
        binding.root.postDelayed(object : Runnable {
            override fun run() {
                if (!isAdded) return
                binding.tvPacketStats.text =
                    "Packets: ${VpnProxyService.packetsProcessed} | Modified: ${VpnProxyService.packetsModified}"
                binding.root.postDelayed(this, 1500)
            }
        }, 1500)
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
