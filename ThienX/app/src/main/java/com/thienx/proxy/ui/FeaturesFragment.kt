package com.thienx.proxy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.thienx.proxy.R
import com.thienx.proxy.databinding.FragmentFeaturesBinding
import com.thienx.proxy.engine.AimConfig
import com.thienx.proxy.engine.EspConfig

class FeaturesFragment : Fragment() {

    private var _binding: FragmentFeaturesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeaturesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSubTabs()
        setupNangCaoTab()
        setupOverlayTab()
        loadSavedConfig()
    }

    private fun setupSubTabs() {
        binding.tabNangCao.setOnClickListener {
            binding.tabNangCao.isSelected = true
            binding.tabOverlay.isSelected = false
            binding.scrollNangCao.visibility = View.VISIBLE
            binding.scrollOverlay.visibility = View.GONE
        }
        binding.tabOverlay.setOnClickListener {
            binding.tabNangCao.isSelected = false
            binding.tabOverlay.isSelected = true
            binding.scrollNangCao.visibility = View.GONE
            binding.scrollOverlay.visibility = View.VISIBLE
        }
        // Default
        binding.tabNangCao.isSelected = true
    }

    // ─── NÂNG CAO TAB ─────────────────────────────────────────────
    private fun setupNangCaoTab() {
        setupHitboxSection()
        setupMocoEsp()
        setupAimLag()
        setupFixDame()
    }

    // Hitbox: ĐẦU / CỔ / NGỰC
    private fun setupHitboxSection() {
        val parts = listOf(
            Triple(binding.btnDau, binding.sliderDau, AimConfig.BonePart.HEAD),
            Triple(binding.btnCo, binding.sliderCo, AimConfig.BonePart.NECK),
            Triple(binding.btnNguc, binding.sliderNguc, AimConfig.BonePart.CHEST),
        )

        var selectedPart = AimConfig.BonePart.HEAD
        binding.btnDau.isSelected = true

        parts.forEach { (btn, slider, part) ->
            btn.setOnClickListener {
                parts.forEach { (b, s, _) ->
                    b.isSelected = false
                    s.visibility = View.GONE
                }
                btn.isSelected = true
                slider.visibility = View.VISIBLE
                selectedPart = part
            }
        }

        // Slider listeners
        binding.sliderDau.addOnChangeListener { _, value, _ ->
            AimConfig.headStrength = value.toInt()
            binding.tvDauPercent.text = "${value.toInt()}%"
            AimConfig.save(requireContext())
        }
        binding.sliderCo.addOnChangeListener { _, value, _ ->
            AimConfig.neckStrength = value.toInt()
            binding.tvCoPercent.text = "${value.toInt()}%"
            AimConfig.save(requireContext())
        }
        binding.sliderNguc.addOnChangeListener { _, value, _ ->
            AimConfig.chestStrength = value.toInt()
            binding.tvNgucPercent.text = "${value.toInt()}%"
            AimConfig.save(requireContext())
        }

        // Hitbox preview drag
        binding.imgHitboxPreview.setOnTouchListener { _, event ->
            val pct = (event.y / binding.imgHitboxPreview.height * 100).coerceIn(0f, 100f)
            when {
                pct < 20 -> {
                    AimConfig.targetBone = AimConfig.BonePart.HEAD
                    binding.tvTargetZone.text = "ĐẦU"
                }
                pct < 40 -> {
                    AimConfig.targetBone = AimConfig.BonePart.NECK
                    binding.tvTargetZone.text = "CỔ"
                }
                else -> {
                    AimConfig.targetBone = AimConfig.BonePart.CHEST
                    binding.tvTargetZone.text = "NGỰC"
                }
            }
            AimConfig.save(requireContext())
            true
        }

        binding.btnRestore.setOnClickListener {
            AimConfig.reset()
            loadSavedConfig()
        }
        binding.btnSaveConfig.setOnClickListener {
            AimConfig.save(requireContext())
            showToast("Đã lưu cấu hình")
        }
        binding.tvFixNote.text = "Kéo trực tiếp trên ảnh hoặc dùng thanh trượt. Chỉ được lệch ±1% so với gốc."
    }

    private fun setupMocoEsp() {
        binding.switchMocoEsp.isChecked = EspConfig.mocoEspEnabled
        binding.switchMocoEsp.setOnCheckedChangeListener { _, checked ->
            EspConfig.mocoEspEnabled = checked
            EspConfig.save(requireContext())
            updateMocoPreview(checked)
        }
    }

    private fun setupAimLag() {
        binding.switchAimLag.isChecked = AimConfig.aimLagEnabled
        binding.switchAimLag.setOnCheckedChangeListener { _, checked ->
            AimConfig.aimLagEnabled = checked
            AimConfig.save(requireContext())
        }

        binding.switchGhost.isChecked = AimConfig.ghostEnabled
        binding.switchGhost.setOnCheckedChangeListener { _, checked ->
            AimConfig.ghostEnabled = checked
            AimConfig.save(requireContext())
        }
    }

    private fun setupFixDame() {
        binding.switchFixDame.isChecked = AimConfig.fixDameEnabled
        binding.switchFixDame.setOnCheckedChangeListener { _, checked ->
            AimConfig.fixDameEnabled = checked
            AimConfig.save(requireContext())
        }
    }

    // ─── OVERLAY TAB ───────────────────────────────────────────────
    private fun setupOverlayTab() {
        // ESP box
        binding.switchEspBox.setOnCheckedChangeListener { _, checked ->
            EspConfig.boxEspEnabled = checked
            EspConfig.save(requireContext())
        }
        // Health bar
        binding.switchHealthBar.setOnCheckedChangeListener { _, checked ->
            EspConfig.healthBarEnabled = checked
            EspConfig.save(requireContext())
        }
        // Name ESP
        binding.switchNameEsp.setOnCheckedChangeListener { _, checked ->
            EspConfig.nameEspEnabled = checked
            EspConfig.save(requireContext())
        }
        // Distance
        binding.switchDistance.setOnCheckedChangeListener { _, checked ->
            EspConfig.distanceEnabled = checked
            EspConfig.save(requireContext())
        }
        // Crosshair
        binding.switchCrosshair.setOnCheckedChangeListener { _, checked ->
            EspConfig.crosshairEnabled = checked
            EspConfig.save(requireContext())
        }

        // Colors
        binding.btnColorBox.setOnClickListener { showColorPicker("box") }
        binding.btnColorName.setOnClickListener { showColorPicker("name") }
    }

    private fun updateMocoPreview(active: Boolean) {
        binding.tvMocoStatus.text = if (active) "ĐANG HOẠT ĐỘNG" else "ĐÃ TẮT"
        binding.tvMocoStatus.setBackgroundColor(
            if (active) 0xCC000000.toInt() else 0xCC440000.toInt()
        )
    }

    private fun loadSavedConfig() {
        AimConfig.load(requireContext())
        EspConfig.load(requireContext())

        binding.sliderDau.value = AimConfig.headStrength.toFloat()
        binding.sliderCo.value = AimConfig.neckStrength.toFloat()
        binding.sliderNguc.value = AimConfig.chestStrength.toFloat()

        binding.tvDauPercent.text = "${AimConfig.headStrength}%"
        binding.tvCoPercent.text = "${AimConfig.neckStrength}%"
        binding.tvNgucPercent.text = "${AimConfig.chestStrength}%"

        binding.switchMocoEsp.isChecked = EspConfig.mocoEspEnabled
        binding.switchAimLag.isChecked = AimConfig.aimLagEnabled
        binding.switchGhost.isChecked = AimConfig.ghostEnabled
        binding.switchFixDame.isChecked = AimConfig.fixDameEnabled

        binding.switchEspBox.isChecked = EspConfig.boxEspEnabled
        binding.switchHealthBar.isChecked = EspConfig.healthBarEnabled
        binding.switchNameEsp.isChecked = EspConfig.nameEspEnabled
        binding.switchDistance.isChecked = EspConfig.distanceEnabled
        binding.switchCrosshair.isChecked = EspConfig.crosshairEnabled

        updateMocoPreview(EspConfig.mocoEspEnabled)
    }

    private fun showColorPicker(target: String) {
        // TODO: integrate color picker dialog
        showToast("Color picker: $target")
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
