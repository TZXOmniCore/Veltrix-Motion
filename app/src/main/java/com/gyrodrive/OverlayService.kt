package com.veltrixmotion

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlayRoot: View? = null
    private lateinit var gyro: GyroManager
    private lateinit var profiles: ProfileManager
    private val handler = Handler(Looper.getMainLooper())

    // Estado
    private var camLocked  = false
    private var camLockDx  = 0f
    private var camLockDy  = 0f
    private var hudVisible = true
    private var useHudStick = false

    // Views
    private var tvSteer:    TextView?    = null
    private var rtBar:      View?        = null
    private var rtBarTrack: FrameLayout? = null
    private var tvRtPct:    TextView?    = null
    private var btnCamLock: TextView?    = null
    private var camView:    View?        = null
    private var hudContent: View?        = null
    private var btnStick:   TextView?    = null

    private val hideRunnable = Runnable { hideHud() }
    private val HIDE_DELAY   = 3000L

    companion object {
        const val ACTION_START     = "com.veltrixmotion.START"
        const val ACTION_STOP      = "com.veltrixmotion.STOP"
        const val ACTION_CALIBRATE = "com.veltrixmotion.CALIBRATE"
        const val CHANNEL_ID       = "veltrix_overlay"
        const val NOTIF_ID         = 1001
    }

    private fun sh(p: Float) = (ScreenMetrics.h * p / 100f).toInt()
    private fun sw(p: Float) = (ScreenMetrics.w * p / 100f).toInt()
    private fun dp(v: Int)   = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        wm       = getSystemService(WINDOW_SERVICE) as WindowManager
        gyro     = GyroManager(this)
        profiles = ProfileManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ScreenMetrics.refresh(this)
                val profile = profiles.getActiveProfile()
                gyro.applyProfile(profile)
                startForeground(NOTIF_ID, buildNotification())
                showOverlay()
                startGyro()
            }
            ACTION_STOP      -> { stopController(); stopSelf() }
            ACTION_CALIBRATE -> startCalibration()
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
        stopController()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Gyro ──────────────────────────────────────────────────────────────
    private fun startGyro() {
        gyro.onSteerChange = { steer, _ ->
            GyroDriveAccessibilityService.instance?.updateSteer(steer)
            handler.post {
                val p = (steer * 100).toInt()
                tvSteer?.text = when {
                    p >  2 -> "► $p%"
                    p < -2 -> "◄ ${-p}%"
                    else   -> "—"
                }
            }
        }
        GyroDriveAccessibilityService.instance?.onRtPressureChange = { p ->
            handler.post { updateRtBar(p) }
        }
        gyro.start()
    }

    // ── Overlay ───────────────────────────────────────────────────────────
    private fun showOverlay() {
        val view = buildHud()
        overlayRoot = view
        wm.addView(view, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START })
        GyroDriveAccessibilityService.instance?.activateController()
        scheduleHide()
    }

    private fun removeOverlay() {
        GyroDriveAccessibilityService.instance?.deactivateController()
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        overlayRoot = null
    }

    private fun stopController() {
        handler.removeCallbacks(hideRunnable)
        GyroDriveAccessibilityService.instance?.deactivateController()
        removeOverlay()
        gyro.stop()
    }

    // ── Modo invisível ────────────────────────────────────────────────────
    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY)
    }
    private fun hideHud() {
        hudContent?.animate()?.alpha(0f)?.setDuration(400)?.start()
        hudVisible = false
    }
    private fun showHud() {
        hudContent?.animate()?.alpha(1f)?.setDuration(200)?.start()
        hudVisible = true
        scheduleHide()
    }
    private fun onUserTouch() {
        if (!hudVisible) showHud() else scheduleHide()
    }

    // ── HUD principal ─────────────────────────────────────────────────────
    private fun buildHud(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.setOnClickListener { onUserTouch() }

        val content = FrameLayout(this)
        hudContent = content

        // TOP BAR
        content.addView(buildTopBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, WRAP, Gravity.TOP
        ))

        // TOPO ESQUERDO: LB + VIEW
        val topLeft = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topLeft.addView(smallBtn("LB",   0xFF0099CC.toInt(), XCloudButton.LB))
        topLeft.addView(smallBtn("VIEW", 0x88FF8C00.toInt(), XCloudButton.VIEW))
        content.addView(topLeft, FrameLayout.LayoutParams(
            WRAP, WRAP, Gravity.TOP or Gravity.START
        ).also { it.topMargin = sh(5f) })

        // TOPO DIREITO: MENU + RB
        val topRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRight.addView(smallBtn("MENU", 0x88FF8C00.toInt(), XCloudButton.MENU))
        topRight.addView(smallBtn("RB",   0xFF0099CC.toInt(), XCloudButton.RB))
        content.addView(topRight, FrameLayout.LayoutParams(
            WRAP, WRAP, Gravity.TOP or Gravity.END
        ).also { it.topMargin = sh(5f) })

        // CENTRO: câmera
        content.addView(buildCameraSection(), FrameLayout.LayoutParams(
            WRAP, WRAP, Gravity.CENTER
        ))

        // BOTTOM
        content.addView(buildBottomBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, WRAP, Gravity.BOTTOM
        ))

        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return root
    }

    // ── TOP BAR ───────────────────────────────────────────────────────────
    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(sw(2f), sh(0.7f), sw(2f), sh(0.7f))
            setBackgroundColor(0xCC060608.toInt())
        }

        // Indicador direção
        tvSteer = tv("— ${gyro.getSensorName().uppercase()} —",
            sh(1.2f).toFloat().coerceAtLeast(11f), 0xFFFF8C00.toInt()).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        bar.addView(tvSteer)

        // RT bar
        val rtSec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(sw(1f), 0, sw(1f), 0)
        }
        tvRtPct = tv("RT 0%", sh(0.85f).toFloat().coerceAtLeast(9f), 0x88FF8C00.toInt()).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        rtSec.addView(tvRtPct)
        rtBarTrack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sw(10f), sh(0.7f))
            background   = GradientDrawable().apply {
                cornerRadius = sh(0.4f).toFloat(); setColor(0x22FF8C00)
            }
        }
        rtBar = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background   = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFFF8C00.toInt(), 0xFFFF2200.toInt())
            ).apply { cornerRadius = sh(0.4f).toFloat() }
        }
        rtBarTrack!!.addView(rtBar)
        rtSec.addView(rtBarTrack)
        bar.addView(rtSec)

        // Toggle gyro/stick
        btnStick = tv("🔄 ${gyro.getSensorName().uppercase()}",
            sh(0.85f).toFloat().coerceAtLeast(9f), 0x88FFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setPadding(sh(0.8f), sh(0.4f), sh(0.8f), sh(0.4f))
            background = GradientDrawable().apply {
                cornerRadius = sh(0.6f).toFloat(); setColor(0x11FFFFFF)
            }
            setOnClickListener { toggleInputMode() }
        }
        bar.addView(btnStick)

        // Stop
        bar.addView(tv("■", sh(1.5f).toFloat().coerceAtLeast(14f), 0xFFFF2B2B.toInt()).apply {
            setPadding(sh(1f), sh(0.5f), sh(1f), sh(0.5f))
            setOnClickListener {
                stopController()
                startService(Intent(this@OverlayService, OverlayService::class.java).apply {
                    action = ACTION_STOP
                })
            }
        })

        return bar
    }

    // ── CÂMERA ────────────────────────────────────────────────────────────
    private fun buildCameraSection(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }
        btnCamLock = tv("🔓 LIVRE", sh(0.85f).toFloat().coerceAtLeast(9f), 0x88FFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setPadding(sh(1f), sh(0.4f), sh(1f), sh(0.4f))
            background = GradientDrawable().apply {
                cornerRadius = sh(0.6f).toFloat(); setColor(0x11FFFFFF)
            }
            setOnClickListener { toggleCamLock() }
        }
        col.addView(btnCamLock)
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, sh(0.5f)) })
        camView = buildCameraArea()
        col.addView(camView)
        return col
    }

    // ── BOTTOM BAR ────────────────────────────────────────────────────────
    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(sw(1f), sh(0.8f), sw(1f), sh(1f))
        }

        // ESQUERDA: stick + dpad
        val leftSide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }
        leftSide.addView(buildHudLeftStick())
        leftSide.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, sh(0.8f)) })
        leftSide.addView(buildDpad())
        bar.addView(leftSide)

        bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        // CENTRO: freio de mão
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        center.addView(buildHandbrake())
        bar.addView(center)

        bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        // DIREITA: face buttons
        val rightSide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        rightSide.addView(circleBtn("Y", 0xFFFFD700.toInt(), XCloudButton.Y))
        val xbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        xbRow.addView(circleBtn("X", 0xFF00C8FF.toInt(), XCloudButton.X))
        xbRow.addView(circleBtn("B", 0xFFFF2B2B.toInt(), XCloudButton.B))
        rightSide.addView(xbRow)
        rightSide.addView(circleBtn("A", 0xFF00FF88.toInt(), XCloudButton.A))
        bar.addView(rightSide)

        bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        // TRIGGERS
        val triggers = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        triggers.addView(buildLT())
        triggers.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, sh(1f)) })
        triggers.addView(buildRT())
        bar.addView(triggers)

        return bar
    }

    // ── ANALÓGICO ESQUERDO HUD ────────────────────────────────────────────
    private fun buildHudLeftStick(): View {
        val size = sh(14f)
        return object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private var tx = 0f; private var ty = 0f; private var touching = false

            init { minimumWidth = size; minimumHeight = size }

            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val r = cx - dp(6)
                paint.style = Paint.Style.FILL
                paint.color = if (useHudStick) 0x22FF8C00.toInt() else 0x0AFF8C00.toInt()
                c.drawOval(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                paint.color = if (useHudStick) 0x88FF8C00.toInt() else 0x33FF8C00.toInt()
                c.drawOval(2f, 2f, width - 2f, height - 2f, paint)
                c.drawLine(cx - r, cy, cx + r, cy, paint)
                c.drawLine(cx, cy - r, cx, cy + r, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (useHudStick) 0xCCFF8C00.toInt() else 0x44FF8C00.toInt()
                if (touching) c.drawCircle(tx, ty, dp(10).toFloat(), paint)
                else c.drawCircle(cx, cy, dp(6).toFloat(), paint)
                paint.textSize = sh(0.8f).toFloat().coerceAtLeast(8f)
                paint.color = 0x44FF8C00.toInt(); paint.textAlign = Paint.Align.CENTER
                c.drawText(if (useHudStick) "STICK" else "GYRO", cx, height - dp(4).toFloat(), paint)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (!useHudStick) return false
                val cx = width / 2f; val cy = height / 2f
                return when (e.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        tx = e.x.coerceIn(0f, width.toFloat())
                        ty = e.y.coerceIn(0f, height.toFloat())
                        touching = true
                        val dx = ((tx - cx) / cx).coerceIn(-1f, 1f)
                        val dy = ((ty - cy) / cy).coerceIn(-1f, 1f)
                        GyroDriveAccessibilityService.instance?.updateHudStick(dx, dy)
                        onUserTouch(); invalidate(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touching = false
                        GyroDriveAccessibilityService.instance?.releaseHudStick()
                        invalidate(); true
                    }
                    else -> false
                }
            }

            private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        }
    }

    // ── D-PAD (sem GridLayout) ────────────────────────────────────────────
    private fun buildDpad(): FrameLayout {
        val btnSz  = sh(5f)
        val gap    = dp(2)
        val total  = btnSz * 3 + gap * 2
        val center = btnSz + gap

        return FrameLayout(this).apply {
            minimumWidth  = total
            minimumHeight = total

            fun dBtn(label: String, btn: XCloudButton, grav: Int) =
                tv(label, sh(1.1f).toFloat().coerceAtLeast(11f), 0xCCFFFFFF.toInt()).apply {
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(btnSz, btnSz, grav)
                    background = GradientDrawable().apply {
                        cornerRadius = sh(0.5f).toFloat(); setColor(0x22FFFFFF)
                    }
                    setOnTouchListener { _, e ->
                        when (e.action) {
                            MotionEvent.ACTION_DOWN -> {
                                setBackgroundColor(0x55FFFFFF)
                                GyroDriveAccessibilityService.instance?.pressButton(btn)
                                onUserTouch(); true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                background = GradientDrawable().apply {
                                    cornerRadius = sh(0.5f).toFloat(); setColor(0x22FFFFFF)
                                }
                                GyroDriveAccessibilityService.instance?.releaseButton(btn); true
                            }
                            else -> false
                        }
                    }
                }

            addView(dBtn("▲", XCloudButton.DPAD_UP,    Gravity.TOP    or Gravity.CENTER_HORIZONTAL))
            addView(dBtn("▼", XCloudButton.DPAD_DOWN,  Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            addView(dBtn("◄", XCloudButton.DPAD_LEFT,  Gravity.START  or Gravity.CENTER_VERTICAL))
            addView(dBtn("►", XCloudButton.DPAD_RIGHT, Gravity.END    or Gravity.CENTER_VERTICAL))

            // Centro decorativo
            addView(View(this@OverlayService).apply {
                val s = btnSz / 2
                layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(0x22FFFFFF)
                }
            })
        }
    }

    // ── FREIO DE MÃO ──────────────────────────────────────────────────────
    private fun buildHandbrake(): FrameLayout {
        val size = sh(11f)
        return FrameLayout(this).apply {
            minimumWidth  = size; minimumHeight = size
            background = GradientDrawable().apply {
                cornerRadius = sh(1f).toFloat(); setColor(0x22FFAA00)
            }

            val col = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val tvIcon = tv("🅿", sh(2.2f).toFloat().coerceAtLeast(20f), 0xFFFFAA00.toInt()).apply {
                gravity = Gravity.CENTER
            }
            val tvLbl = tv("HAND\nBRAKE", sh(0.8f).toFloat().coerceAtLeast(8f), 0xCCFFAA00.toInt()).apply {
                gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE
            }
            col.addView(tvIcon); col.addView(tvLbl)
            addView(col)

            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        background = GradientDrawable().apply {
                            cornerRadius = sh(1f).toFloat(); setColor(0xCCFFAA00.toInt())
                        }
                        tvIcon.setTextColor(Color.BLACK); tvLbl.setTextColor(Color.BLACK)
                        GyroDriveAccessibilityService.instance?.holdHandbrake()
                        onUserTouch(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = GradientDrawable().apply {
                            cornerRadius = sh(1f).toFloat(); setColor(0x22FFAA00)
                        }
                        tvIcon.setTextColor(0xFFFFAA00.toInt()); tvLbl.setTextColor(0xCCFFAA00.toInt())
                        GyroDriveAccessibilityService.instance?.releaseHandbrake(); true
                    }
                    else -> false
                }
            }
        }
    }

    // ── RT ────────────────────────────────────────────────────────────────
    private fun buildRT(): FrameLayout {
        val w = sh(13f); val h = sh(10f)
        return buildTrigger("⚡", "RT\nACEL", 0xFFFF8C00.toInt(), w, h,
            onDown = { GyroDriveAccessibilityService.instance?.startRT() },
            onUp   = { GyroDriveAccessibilityService.instance?.stopRT() }
        )
    }

    // ── LT ────────────────────────────────────────────────────────────────
    private fun buildLT(): FrameLayout {
        val w = sh(13f); val h = sh(10f)
        return buildTrigger("🛑", "LT\nFREIO", 0xFF00C8FF.toInt(), w, h,
            onDown = { GyroDriveAccessibilityService.instance?.startLT() },
            onUp   = { GyroDriveAccessibilityService.instance?.stopLT() }
        )
    }

    private fun buildTrigger(
        icon: String, label: String, color: Int, w: Int, h: Int,
        onDown: () -> Unit, onUp: () -> Unit
    ): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).also {
                it.setMargins(sh(0.3f), sh(0.3f), sh(0.3f), sh(0.3f))
            }
            background = GradientDrawable().apply {
                cornerRadius = sh(1f).toFloat(); setColor(Color.TRANSPARENT); setStroke(2, color)
            }
            val col = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val tvI = tv(icon,  sh(2f).toFloat().coerceAtLeast(18f), color).apply { gravity = Gravity.CENTER }
            val tvL = tv(label, sh(0.8f).toFloat().coerceAtLeast(8f), color).apply {
                gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE; letterSpacing = 0.08f
            }
            col.addView(tvI); col.addView(tvL)
            addView(col)

            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        background = GradientDrawable().apply {
                            cornerRadius = sh(1f).toFloat(); setColor(color)
                        }
                        tvI.setTextColor(Color.BLACK); tvL.setTextColor(Color.BLACK)
                        onDown(); onUserTouch(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = GradientDrawable().apply {
                            cornerRadius = sh(1f).toFloat()
                            setColor(Color.TRANSPARENT); setStroke(2, color)
                        }
                        tvI.setTextColor(color); tvL.setTextColor(color)
                        onUp(); true
                    }
                    else -> false
                }
            }
        }
    }

    // ── ÁREA DE CÂMERA ────────────────────────────────────────────────────
    private fun buildCameraArea(): View {
        val size = sh(16f)
        return object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private var tx = 0f; private var ty = 0f; private var touching = false
            init { minimumWidth = size; minimumHeight = size }

            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val r = cx - dp(4)
                paint.style = Paint.Style.FILL
                paint.color = if (camLocked) 0x2200C8FF.toInt() else 0x1100C8FF.toInt()
                c.drawRoundRect(4f, 4f, width - 4f, height - 4f, dp(12).toFloat(), dp(12).toFloat(), paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                paint.color = if (camLocked) 0x8800C8FF.toInt() else 0x3300C8FF.toInt()
                c.drawRoundRect(4f, 4f, width - 4f, height - 4f, dp(12).toFloat(), dp(12).toFloat(), paint)
                c.drawLine(cx - r, cy, cx + r, cy, paint)
                c.drawLine(cx, cy - r, cx, cy + r, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (camLocked) 0xCC00C8FF.toInt() else 0x6600C8FF.toInt()
                c.drawCircle(cx, cy, dp(5).toFloat(), paint)
                if (touching || (camLocked && (abs(camLockDx) > 0.05f || abs(camLockDy) > 0.05f))) {
                    val dotX = if (camLocked) cx + camLockDx * r else tx
                    val dotY = if (camLocked) cy + camLockDy * r else ty
                    paint.color = 0xCC00C8FF.toInt()
                    c.drawCircle(dotX, dotY, dp(8).toFloat(), paint)
                }
                paint.textSize = sh(0.8f).toFloat().coerceAtLeast(8f)
                paint.color = 0x4400C8FF.toInt(); paint.textAlign = Paint.Align.CENTER
                c.drawText(if (camLocked) "🔒 TRAVADA" else "CÂMERA", cx, height - dp(5).toFloat(), paint)
            }

            private fun abs(v: Float) = if (v < 0) -v else v

            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (camLocked) return false
                val cx = width / 2f; val cy = height / 2f
                return when (e.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        tx = e.x.coerceIn(0f, width.toFloat())
                        ty = e.y.coerceIn(0f, height.toFloat())
                        touching = true
                        val dx = ((tx - cx) / cx).coerceIn(-1f, 1f)
                        val dy = ((ty - cy) / cy).coerceIn(-1f, 1f)
                        camLockDx = dx; camLockDy = dy
                        GyroDriveAccessibilityService.instance?.moveCameraStick(dx, dy)
                        onUserTouch(); invalidate(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touching = false; camLockDx = 0f; camLockDy = 0f
                        GyroDriveAccessibilityService.instance?.releaseCameraStick()
                        invalidate(); true
                    }
                    else -> false
                }
            }

            private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        }
    }

    // ── Ações ─────────────────────────────────────────────────────────────
    private fun toggleInputMode() {
        useHudStick = !useHudStick
        GyroDriveAccessibilityService.instance?.useHudLeftStick = useHudStick
        btnStick?.text = if (useHudStick) "🕹 STICK" else "🔄 ${gyro.getSensorName().uppercase()}"
        onUserTouch()
    }

    private fun toggleCamLock() {
        val svc = GyroDriveAccessibilityService.instance
        camLocked = !camLocked
        if (camLocked) {
            svc?.lockCamera(camLockDx, camLockDy)
            btnCamLock?.text = "🔒 TRAVADA"
            btnCamLock?.setTextColor(0xFF00C8FF.toInt())
        } else {
            svc?.unlockCamera()
            btnCamLock?.text = "🔓 LIVRE"
            btnCamLock?.setTextColor(0x88FFFFFF.toInt())
        }
        camView?.invalidate(); onUserTouch()
    }

    private fun startCalibration() {
        CalibrationManager(
            context    = this,
            wm         = wm,
            onComplete = { map ->
                GyroDriveAccessibilityService.instance?.touchMap = map
                val active = profiles.getActiveProfile()
                profiles.saveProfile(active.copy(touchMap = map))
            },
            onCancel = {}
        ).start()
    }

    private fun updateRtBar(pressure: Float) {
        tvRtPct?.text = "RT ${(pressure * 100).toInt()}%"
        val track = rtBarTrack ?: return
        val bar   = rtBar      ?: return
        val lp    = bar.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width  = (track.width * pressure).toInt().coerceAtLeast(0)
        bar.layoutParams = lp
    }

    // ── Botões ────────────────────────────────────────────────────────────
    private fun circleBtn(label: String, color: Int, button: XCloudButton): TextView {
        val size = sh(7f)
        return tv(label, sh(1.4f).toFloat().coerceAtLeast(13f), color).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.setMargins(sh(0.3f), sh(0.3f), sh(0.3f), sh(0.3f))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(sh(0.18f), color)
            }
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(color); setTextColor(Color.BLACK)
                        GyroDriveAccessibilityService.instance?.pressButton(button)
                        onUserTouch(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(sh(0.18f), color)
                        }
                        setTextColor(color)
                        GyroDriveAccessibilityService.instance?.releaseButton(button); true
                    }
                    else -> false
                }
            }
        }
    }

    private fun smallBtn(label: String, color: Int, button: XCloudButton): TextView {
        return tv(label, sh(0.85f).toFloat().coerceAtLeast(9f), color).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(sh(7f), sh(4.5f)).also {
                it.setMargins(sh(0.25f), sh(0.25f), sh(0.25f), sh(0.25f))
            }
            background = GradientDrawable().apply {
                cornerRadius = sh(0.7f).toFloat(); setColor(Color.TRANSPARENT); setStroke(1, color)
            }
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(color); setTextColor(Color.BLACK)
                        GyroDriveAccessibilityService.instance?.pressButton(button)
                        onUserTouch(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = GradientDrawable().apply {
                            cornerRadius = sh(0.7f).toFloat(); setColor(Color.TRANSPARENT); setStroke(1, color)
                        }
                        setTextColor(color)
                        GyroDriveAccessibilityService.instance?.releaseButton(button); true
                    }
                    else -> false
                }
            }
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────
    private fun tv(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Notification ─────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Veltrix Motion Overlay", NotificationManager.IMPORTANCE_LOW
                ))
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Veltrix Motion")
        .setContentText("${profiles.getActiveProfile().emoji} ${profiles.getActiveProfile().name} · Sensor: ${gyro.getSensorName()}")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentIntent(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        ))
        .addAction(android.R.drawable.ic_media_pause, "PARAR",
            PendingIntent.getService(this, 1,
                Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
}
