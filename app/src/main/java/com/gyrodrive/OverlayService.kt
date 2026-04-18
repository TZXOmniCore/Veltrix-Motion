package com.gyrodrive

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * Versão LEVE do overlay — otimizada para dispositivos com 3GB RAM.
 * 
 * Mudanças vs versão anterior:
 * - Sem Canvas customizado (elimina redraw contínuo)
 * - Sem coroutines no overlay (apenas no AccessibilityService)
 * - Botões simples sem animação
 * - HUD mínimo: só steering bar + botões essenciais
 * - Sem câmera analog (economiza memória e CPU)
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlayRoot: View? = null
    private lateinit var gyro: GyroManager
    private val handler = Handler(Looper.getMainLooper())

    // Views mínimas
    private var tvSteer: TextView? = null

    companion object {
        const val ACTION_START = "com.gyrodrive.START"
        const val ACTION_STOP  = "com.gyrodrive.STOP"
        const val CHANNEL_ID   = "gyrodrive_overlay"
        const val NOTIF_ID     = 1001
    }

    // Tamanhos relativos à tela
    private fun sh(pct: Float) = (ScreenMetrics.h * pct / 100f).toInt()
    private fun sw(pct: Float) = (ScreenMetrics.w * pct / 100f).toInt()

    override fun onCreate() {
        super.onCreate()
        wm   = getSystemService(WINDOW_SERVICE) as WindowManager
        gyro = GyroManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ScreenMetrics.refresh(this)
                startForeground(NOTIF_ID, buildNotification())
                showOverlay()
                startGyro()
            }
            ACTION_STOP -> { removeOverlay(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ScreenMetrics.refresh(this)
        if (overlayRoot != null) { removeOverlay(); showOverlay() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        gyro.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Giroscópio ───────────────────────────────────────────────────────
    private fun startGyro() {
        gyro.onSteerChange = { steer, _ ->
            GyroDriveAccessibilityService.instance?.updateSteer(steer)
            handler.post {
                val pct = (steer * 100).toInt()
                tvSteer?.text = if (pct == 0) "—" else if (pct > 0) "► $pct%" else "◄ ${-pct}%"
            }
        }
        gyro.start()
    }

    // ── Overlay ──────────────────────────────────────────────────────────
    private fun showOverlay() {
        val view = buildHud()
        overlayRoot = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, params)
    }

    private fun removeOverlay() {
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        overlayRoot = null
        gyro.stop()
    }

    // ── HUD LEVE ─────────────────────────────────────────────────────────
    private fun buildHud(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // ── Barra superior fina ──────────────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sw(2f), sh(0.8f), sw(2f), sh(0.8f))
            setBackgroundColor(0xCC060608.toInt())
        }

        // Indicador de direção (texto simples, sem canvas)
        tvSteer = TextView(this).apply {
            text = "—"
            textSize = sh(1.3f).toFloat().coerceAtLeast(12f)
            setTextColor(0xFFFF8C00.toInt())
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(tvSteer)

        // Botão parar
        topBar.addView(TextView(this).apply {
            text = "■ PARAR"
            textSize = sh(1f).toFloat().coerceAtLeast(10f)
            setTextColor(0xFFFF2B2B.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(sh(1f), sh(0.5f), sh(1f), sh(0.5f))
            background = strokeRect(0x44FF2B2B.toInt(), sh(0.8f).toFloat())
            setOnClickListener {
                startService(Intent(this@OverlayService, OverlayService::class.java).apply {
                    action = ACTION_STOP
                })
            }
        })

        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        // ── Botões essenciais embaixo ─────────────────────────────────────
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sw(1f), sh(1f), sw(1f), sh(1f))
        }

        // Lado esquerdo: Y, X, A, B
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        leftCol.addView(btn("Y",  0xFFFFD700.toInt(), XCloudButton.Y))
        val midRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        midRow.addView(btn("X", 0xFF00C8FF.toInt(), XCloudButton.X))
        midRow.addView(btn("A", 0xFF00FF88.toInt(), XCloudButton.A))
        leftCol.addView(midRow)
        leftCol.addView(btn("B",  0xFFFF2B2B.toInt(), XCloudButton.B))
        bottomBar.addView(leftCol)

        // Espaço central
        bottomBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        // Lado direito: LT, RT
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rightCol.addView(trigBtn("🛑 LT", 0xFF00C8FF.toInt(), XCloudButton.LT))
        rightCol.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, sh(1f)) })
        rightCol.addView(trigBtn("⚡ RT", 0xFFFF8C00.toInt(), XCloudButton.RT))
        bottomBar.addView(rightCol)

        root.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        // ── Botões extras no topo esquerdo ───────────────────────────────
        val topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLeft.addView(smallBtn("LB",   0xFF0099CC.toInt(), XCloudButton.LB))
        topLeft.addView(smallBtn("VIEW", 0x88FF8C00.toInt(), XCloudButton.VIEW))

        root.addView(topLeft, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).also { it.setMargins(0, sh(4f), 0, 0) })

        // Topo direito
        val topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRight.addView(smallBtn("MENU", 0x88FF8C00.toInt(), XCloudButton.MENU))
        topRight.addView(smallBtn("RB",   0xFF0099CC.toInt(), XCloudButton.RB))

        root.addView(topRight, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).also { it.setMargins(0, sh(4f), 0, 0) })

        return root
    }

    // ── Botão circular ───────────────────────────────────────────────────
    private fun btn(label: String, color: Int, button: XCloudButton): TextView {
        val size = sh(8f)
        return TextView(this).apply {
            text = label
            textSize = sh(1.4f).toFloat().coerceAtLeast(13f)
            setTextColor(color)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.setMargins(sh(0.4f), sh(0.4f), sh(0.4f), sh(0.4f))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(sh(0.2f), color)
            }
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(color)
                        setTextColor(Color.BLACK)
                        GyroDriveAccessibilityService.instance?.pressButton(button)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(sh(0.2f), color)
                        }
                        setTextColor(color)
                        GyroDriveAccessibilityService.instance?.releaseButton(button)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // ── Trigger (LT/RT) ──────────────────────────────────────────────────
    private fun trigBtn(label: String, color: Int, button: XCloudButton): TextView {
        val size = sh(10f)
        return TextView(this).apply {
            text = label
            textSize = sh(1.3f).toFloat().coerceAtLeast(12f)
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(sh(14f), size).also {
                it.setMargins(sh(0.3f), sh(0.3f), sh(0.3f), sh(0.3f))
            }
            background = strokeRect(color and 0x44FFFFFF, sh(1f).toFloat())
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(color)
                        setTextColor(Color.BLACK)
                        GyroDriveAccessibilityService.instance?.pressButton(button)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = strokeRect(color and 0x44FFFFFF, sh(1f).toFloat())
                        setTextColor(color)
                        GyroDriveAccessibilityService.instance?.releaseButton(button)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // ── Botão pequeno ────────────────────────────────────────────────────
    private fun smallBtn(label: String, color: Int, button: XCloudButton): TextView {
        val size = sh(5f)
        return TextView(this).apply {
            text = label
            textSize = sh(0.9f).toFloat().coerceAtLeast(9f)
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(sh(8f), size).also {
                it.setMargins(sh(0.3f), sh(0.3f), sh(0.3f), sh(0.3f))
            }
            background = strokeRect(color and 0x33FFFFFF, sh(0.8f).toFloat())
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(color)
                        setTextColor(Color.BLACK)
                        GyroDriveAccessibilityService.instance?.pressButton(button)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = strokeRect(color and 0x33FFFFFF, sh(0.8f).toFloat())
                        setTextColor(color)
                        GyroDriveAccessibilityService.instance?.releaseButton(button)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // ── Drawables ─────────────────────────────────────────────────────────
    private fun strokeRect(color: Int, radius: Float) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(2, color)
        }

    // ── Notification ─────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "GyroDrive Overlay",
                    NotificationManager.IMPORTANCE_LOW
                ))
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("GyroDrive ativo")
        .setContentText("Incline o celular para dirigir")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentIntent(PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        ))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
