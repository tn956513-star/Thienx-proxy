package com.thienx.proxy.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import com.thienx.proxy.R
import com.thienx.proxy.engine.AimConfig
import com.thienx.proxy.engine.EspConfig
import com.thienx.proxy.network.VpnProxyService

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var espCanvas: EspOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(2, buildNotif())
        createOverlay()
        createEspCanvas()
    }

    override fun onDestroy() {
        overlayView?.let { wm.removeView(it) }
        espCanvas?.let  { wm.removeView(it) }
        super.onDestroy()
    }

    // ─── Floating Control Button ─────────────────────────────────────
    private fun createOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 200
        }

        val btn = buildControlButton()
        overlayView = btn
        wm.addView(btn, params)
        setupDrag(btn, params)
    }

    private fun buildControlButton(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xCC0A0A1E.toInt())
            // Rounded corners via outline
        }

        val tvName = TextView(this).apply {
            text = "⚡TX"
            textSize = 10f
            setTextColor(0xFF7878FF.toInt())
            gravity = Gravity.CENTER
        }

        val switchBtn = Switch(this).apply {
            isChecked = VpnProxyService.isRunning
            textSize = 9f
            setTextColor(0xFFCCCCCC.toInt())
            text = if (isChecked) "ON" else "OFF"

            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "ON" else "OFF"
                AimConfig.aimEnabled = checked
            }
        }

        val tvStats = TextView(this).apply {
            textSize = 7f
            setTextColor(0xFF888888.toInt())
            text = "0 pkt"

            // Update stats periodically
            postDelayed(object : Runnable {
                override fun run() {
                    text = "${VpnProxyService.packetsModified}/${VpnProxyService.packetsProcessed}"
                    postDelayed(this, 1000)
                }
            }, 1000)
        }

        layout.addView(tvName)
        layout.addView(switchBtn)
        layout.addView(tvStats)
        return layout
    }

    // ─── ESP Canvas Overlay ──────────────────────────────────────────
    private fun createEspCanvas() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val canvas = EspOverlayView(this)
        espCanvas = canvas
        wm.addView(canvas, params)
    }

    // ─── Drag support ────────────────────────────────────────────────
    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var lx = 0; var ly = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = event.rawX.toInt(); iy = event.rawY.toInt()
                    lx = params.x;           ly = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = lx + (event.rawX.toInt() - ix)
                    params.y = ly + (event.rawY.toInt() - iy)
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun buildNotif(): Notification {
        val ch = "thienx_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(ch, "Overlay", NotificationManager.IMPORTANCE_MIN)
                )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ch)
                .setContentTitle("Thiên X Overlay")
                .setSmallIcon(R.drawable.ic_logo)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Thiên X Overlay")
                .setSmallIcon(R.drawable.ic_logo)
                .setOngoing(true).build()
        }
    }
}

// ─── ESP Canvas View ─────────────────────────────────────────────
class EspOverlayView(ctx: android.content.Context) : View(ctx) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textSize = 28f
        isAntiAlias = true
        color = Color.WHITE
    }
    private val healthPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val crosshairPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.GREEN
        isAntiAlias = true
    }

    // Enemy data provided by packet parser / game state reader
    data class EnemyMarker(
        val x: Float, val y: Float,   // screen coords
        val w: Float, val h: Float,   // box size
        val name: String,
        val healthPct: Float,          // 0..1
        val distance: Float,           // meters
    )

    var enemies: List<EnemyMarker> = emptyList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!EspConfig.boxEspEnabled && !EspConfig.crosshairEnabled) return

        // Crosshair
        if (EspConfig.crosshairEnabled) {
            drawCrosshair(canvas)
        }

        // Enemy markers
        for (e in enemies) {
            boxPaint.color = EspConfig.boxColor
            if (EspConfig.boxEspEnabled) {
                canvas.drawRect(e.x, e.y, e.x + e.w, e.y + e.h, boxPaint)
            }
            if (EspConfig.healthBarEnabled) {
                drawHealthBar(canvas, e)
            }
            if (EspConfig.nameEspEnabled) {
                textPaint.color = EspConfig.nameColor
                canvas.drawText(e.name, e.x, e.y - 4f, textPaint)
            }
            if (EspConfig.distanceEnabled) {
                textPaint.color = Color.YELLOW
                textPaint.textSize = 22f
                canvas.drawText("${e.distance.toInt()}m", e.x, e.y + e.h + 18f, textPaint)
            }
        }

        // Redraw every 50ms
        postInvalidateDelayed(50)
    }

    private fun drawHealthBar(canvas: Canvas, e: EnemyMarker) {
        val barW = 4f
        val left = e.x - barW - 2f
        val top  = e.y
        val btm  = e.y + e.h

        // Background
        healthPaint.color = Color.argb(180, 50, 0, 0)
        canvas.drawRect(left, top, left + barW, btm, healthPaint)

        // Health
        val hColor = when {
            e.healthPct > 0.5f -> Color.GREEN
            e.healthPct > 0.25f -> Color.YELLOW
            else -> Color.RED
        }
        healthPaint.color = hColor
        val hTop = top + (btm - top) * (1f - e.healthPct)
        canvas.drawRect(left, hTop, left + barW, btm, healthPaint)
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val sz = 20f
        canvas.drawLine(cx - sz, cy, cx + sz, cy, crosshairPaint)
        canvas.drawLine(cx, cy - sz, cx, cy + sz, crosshairPaint)
        canvas.drawCircle(cx, cy, 3f, crosshairPaint)
    }
}
