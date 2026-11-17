package com.thienx.proxy.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.thienx.proxy.R
import com.thienx.proxy.network.VpnProxyService
import com.thienx.proxy.system.KeyManager
import com.thienx.proxy.system.GameConfig
import com.thienx.proxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val VPN_REQUEST = 1001
    private val OVERLAY_REQUEST = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()
        setupHeader()
        checkPermissions()

        // Load home fragment by default
        loadFragment(HomeFragment())
    }

    private fun setupHeader() {
        binding.tvAppName.text = "THIÊN X"
        binding.btnRefresh.setOnClickListener {
            GameConfig.refresh(this)
            showToast("Đã làm mới cấu hình")
        }
        binding.btnNotification.setOnClickListener {
            loadFragment(NotificationFragment())
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.nav_features -> {
                    if (KeyManager.isKeyValid(this)) {
                        loadFragment(FeaturesFragment())
                    } else {
                        loadFragment(HomeFragment())
                        showToast("Cần key hợp lệ để truy cập tính năng")
                    }
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun checkPermissions() {
        // Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_REQUEST)
        }
    }

    fun startProxy() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST)
        } else {
            launchVpnService()
        }
    }

    fun stopProxy() {
        val intent = Intent(this, VpnProxyService::class.java)
        intent.action = VpnProxyService.ACTION_STOP
        startService(intent)
        stopService(Intent(this, OverlayService::class.java))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VPN_REQUEST -> {
                if (resultCode == RESULT_OK) launchVpnService()
            }
            OVERLAY_REQUEST -> {
                if (!Settings.canDrawOverlays(this)) {
                    showToast("Cần quyền hiển thị trên ứng dụng khác")
                }
            }
        }
    }

    private fun launchVpnService() {
        val intent = Intent(this, VpnProxyService::class.java)
        intent.action = VpnProxyService.ACTION_START
        startForegroundService(intent)

        // Start overlay
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
