package com.gyrodrive

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * Serviço de overlay HUD.
 *
 * Responsividade:
 *   - Todos os tamanhos de botões, margens e fontes são calculados
 *     a partir de ScreenMetrics.h (altura real da tela) via sh() / sw()
 *   - Funciona em 720p, 1080p, 1440p, tablets, dobráveis
 *   - Recalcula ao receber onConfigurationChanged (rotação, fold)
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlayRoot: View? = null
    private lateinit var gyro: GyroManager

    // Views do HUD atualizadas em runtime
    private var tvSteer: TextView?    = null
    private var tvAngle: TextView?    = null
    private var steerDot: View?       = null
    private var steerTrack: FrameLayout? = null

    companion object {
        const val ACTION_START = "com.gyrodrive.START"
        const val ACTION_STOP  = "com.gyrodrive.STOP"
        const val CHANNEL_ID   = "gyrodrive_overlay"
        const val NOTIF_ID     = 1001
    }

    // ── Dimensionamento responsivo ───────────────────────────────────────
    //
    // Tudo derivado de sh() = 1% da altura real da tela.
    // Numa tela 1080px alta:  sh(1) = 10.8px,  sh(8) = 86px
    // Numa tela 720px alta:   sh(1) = 7.2px,   sh(8) = 57px
    // → botões e fontes escalam proporcionalmente em qualquer modelo.

    private fun sh(pct: Float) = (ScreenMetrics.h * pct / 100f).toInt()
    private fun sw(pct: Float) = (ScreenMetrics.w * pct / 100f).toInt()

    private val btnSize   get() = sh(9f)    // ~9% da altura
    private val trigSize  get() = sh(11f)   // triggers um pouco maiores
    private val fontSize  get() = sh(1.2f).toFloat().coerceAtLeast(10f)
    private val iconSize  get() = sh(3f).toFloat().coerceAtLeast(20f)
    private val margin    get() = sh(1.5f)
    private val radius    get() = sh(1.2f).toFloat()

    // ── Lifecycle ────────────────────────────────────────────────────────
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
        // Tela rotacionou ou dobrou — recalcula e reconstrói o HUD
        ScreenMetrics.refresh(this)
        GyroDriveAccessibilityService.instance?.let {
            ScreenMetrics.refresh(this) // sincroniza o serviço também
        }
        if (overlayRoot != null) {
            removeOverlay()
            showOverlay()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        gyro.stop()
        super.onDestroy()
    }

    // ── Giroscópio ───────────────────────────────────────────────────────
    private fun startGyro() {
        gyro.onSteerChange = { steer, rawDeg ->
            GyroDriveAccessibilityService.instance?.updateSteer(steer)
            Handler(Looper.getMainLooper()).post { updateHud(steer, rawDeg) }
        }
        gyro.start()
    }

    // ── Overlay ──────────────────────────────────────────────────────────
    private fun showOverlay() {
        val view = buildHud()
        overlayRoot = view

        val params = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        wm.addView(view, params)
    }

    private fun removeOverlay() {
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        overlayRoot = null
        gyro.stop()
    }

    // ── Construção do HUD ────────────────────────────────────────────────
    private fun buildHud(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        root.addView(buildTopBar(),   hudLP(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(buildLeftCluster(),  hudLP(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START,  ml = margin, mb = margin))
        root.addView(buildRightTriggers(), hudLP(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,   mr = margin, mb = margin))
        root.addView(buildTopLeft(),  hudLP(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP    or Gravity.START, ml = margin, mt = sh(5f)))
        root.addView(buildTopRight(), hudLP(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP    or Gravity.END,   mr = margin, mt = sh(5f)))
        root.addView(buildCenter(),   hudLP(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        return root
    }

    // ── Top bar (steering indicator) ─────────────────────────────────────
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sw(1.5f), sh(0.8f), sw(1.5f), sh(0.8f))
            setBackgroundColor(0xCC060608.toInt())
        }

        // Settings
        bar.addView(hudText("⚙", fontSize) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        })
        bar.addView(spacer(sw(1f), 1))

        // Steering track
        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, sh(1.2f), 1f)
            background = roundRect(0x22FF8C00.toInt(), radius)
        }
        steerDot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(sh(1.5f), sh(1.5f), Gravity.CENTER)
            background = circle(0xFFFF8C00.toInt())
            elevation = 4f
        }
        track.addView(steerDot)
        steerTrack = track
        bar.addView(track)

        bar.addView(spacer(sw(1f), 1))

        tvAngle = TextView(this).apply {
            text = "γ 0°"; textSize = fontSize * 0.75f
            setTextColor(0x88FF8C00.toInt()); typeface = Typeface.MONOSPACE
            setPadding(sw(0.5f), 0, sw(0.5f), 0)
        }
        bar.addView(tvAngle)

        tvSteer = TextView(this).apply {
            text = "0%"; textSize = fontSize * 0.75f
            setTextColor(0x88FF8C00.toInt()); typeface = Typeface.MONOSPACE
            setPadding(sw(0.5f), 0, sw(1f), 0)
        }
        bar.addView(tvSteer)

        // Status dot
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(sh(1f), sh(1f)).also { it.gravity = Gravity.CENTER_VERTICAL }
            background = circle(if (GyroDriveAccessibilityService.isActive()) 0xFF00FF88.toInt() else 0xFFFF2B2B.toInt())
        }
        bar.addView(dot)

        return bar
    }

    // ── Cluster esquerdo: Y / X+A / B ────────────────────────────────────
    private fun buildLeftCluster(): LinearLayout {
        val col = column()
        col.addView(circleBtn("Y",  0xFFFFD700.toInt(), XCloudButton.Y))
        val row = row()
        row.addView(circleBtn("X", 0xFF00C8FF.toInt(), XCloudButton.X))
        row.addView(spacer(sh(0.8f), 1))
        row.addView(circleBtn("A", 0xFF00FF88.toInt(), XCloudButton.A))
        col.addView(row)
        col.addView(circleBtn("B",  0xFFFF2B2B.toInt(), XCloudButton.B))
        return col
    }

    // ── LT + RT (direita) ────────────────────────────────────────────────
    private fun buildRightTriggers(): LinearLayout {
        val col = column()
        col.addView(triggerBtn("🛑", "LT", 0xFF00C8FF.toInt(), XCloudButton.LT))
        col.addView(spacer(1, sh(1.2f)))
        col.addView(triggerBtn("⚡", "RT", 0xFFFF8C00.toInt(), XCloudButton.RT))
        return col
    }

    // ── LB + VIEW (topo esq) ─────────────────────────────────────────────
    private fun buildTopLeft(): LinearLayout {
        val r = row()
        r.addView(smallBtn("LB",    0xFF0099CC.toInt(), XCloudButton.LB))
        r.addView(spacer(sh(0.8f), 1))
        r.addView(smallBtn("⊟\nVIEW", 0x88FF8C00.toInt(), XCloudButton.VIEW))
        return r
    }

    // ── RB + MENU (topo dir) ─────────────────────────────────────────────
    private fun buildTopRight(): LinearLayout {
        val r = row()
        r.addView(smallBtn("↺\nRWND", 0x88FF8C00.toInt(), XCloudButton.REWIND))
        r.addView(spacer(sh(0.8f), 1))
        r.addView(smallBtn("≡\nMENU",  0x88FF8C00.toInt(), XCloudButton.MENU))
        r.addView(spacer(sh(0.8f), 1))
        r.addView(smallBtn("RB",       0xFF0099CC.toInt(), XCloudButton.RB))
        return r
    }

    // ── Camera analog area (centro) ───────────────────────────────────────
    private fun buildCenter(): View {
        val size = sh(14f)  // 14% da altura = proporcional em qualquer tela
        return object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private var tx = 0f; private var ty = 0f; private var touching = false

            init { minimumWidth = size; minimumHeight = size }

            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val r = cx - 8f
                paint.style = Paint.Style.FILL
                paint.color = if (touching) 0x2200C8FF.toInt() else 0x1100C8FF.toInt()
                c.drawRoundRect(4f, 4f, width - 4f, height - 4f, radius, radius, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                paint.color = 0x4400C8FF.toInt()
                c.drawRoundRect(4f, 4f, width - 4f, height - 4f, radius, radius, paint)
                c.drawLine(cx - r, cy, cx + r, cy, paint)
                c.drawLine(cx, cy - r, cx, cy + r, paint)
                paint.style = Paint.Style.FILL; paint.color = 0x6600C8FF.toInt()
                c.drawCircle(cx, cy, 8f, paint)
                if (touching) {
                    paint.color = 0xAA00C8FF.toInt()
                    c.drawCircle(tx, ty, sh(1.5f).toFloat(), paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                    paint.color = 0x6600C8FF.toInt()
                    c.drawLine(cx, cy, tx, ty, paint)
                }
                // Label
                paint.style = Paint.Style.FILL
                paint.color = 0x4400C8FF.toInt()
                paint.textSize = fontSize * 0.8f
                paint.textAlign = Paint.Align.CENTER
                c.drawText("CÂMERA", cx, height - sh(1f).toFloat(), paint)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                val cx = width / 2f; val cy = height / 2f
                return when (e.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        tx = e.x.coerceIn(0f, width.toFloat())
                        ty = e.y.coerceIn(0f, height.toFloat())
                        touching = true
                        val dx = ((tx - cx) / cx).coerceIn(-1f, 1f)
                        val dy = ((ty - cy) / cy).coerceIn(-1f, 1f)
                        GyroDriveAccessibilityService.instance?.moveCameraStick(dx, dy)
                        invalidate(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touching = false; tx = cx; ty = cy
                        GyroDriveAccessibilityService.instance?.releaseCameraStick()
                        invalidate(); true
                    }
                    else -> false
                }
            }
        }
    }

    // ── HUD update ────────────────────────────────────────────────────────
    private fun updateHud(steer: Float, rawDeg: Float) {
        tvSteer?.text = "${(steer * 100).toInt()}%"
        tvAngle?.text = "γ ${rawDeg.toInt()}°"

        val track = steerTrack ?: return
        val dot   = steerDot   ?: return
        val tw = track.width.toFloat()
        if (tw == 0f) return

        val lp = dot.layoutParams as FrameLayout.LayoutParams
        val offset = (steer * tw / 2f).toInt()
        lp.leftMargin  = tw.toInt() / 2 + offset - dot.width / 2
        lp.gravity     = Gravity.CENTER_VERTICAL or Gravity.START
        dot.layoutParams = lp
    }

    // ── Widget builders ───────────────────────────────────────────────────

    private fun circleBtn(label: String, color: Int, btn: XCloudButton) =
        pressableView(btnSize, btnSize, true, color, label, iconSize, btn)

    private fun smallBtn(label: String, color: Int, btn: XCloudButton) =
        pressableView((btnSize * 0.75f).toInt(), (btnSize * 0.75f).toInt(), false, color, label, fontSize, btn)

    private fun triggerBtn(icon: String, lbl: String, color: Int, btn: XCloudButton): View {
        val w = (trigSize * 1.1f).toInt()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = w; minimumHeight = w
            background = strokeRoundRect(color, radius, strokeW = sh(0.2f).toFloat())
            layoutParams = LinearLayout.LayoutParams(w, w).also {
                it.setMargins(0, sh(0.5f), 0, sh(0.5f))
            }
        }
        val tv1 = TextView(this).apply { text = icon; textSize = iconSize * 1.2f; gravity = Gravity.CENTER; setTextColor(color) }
        val tv2 = TextView(this).apply { text = lbl;  textSize = fontSize * 0.85f; gravity = Gravity.CENTER; setTextColor(color); typeface = Typeface.MONOSPACE; letterSpacing = 0.12f }
        v.addView(tv1); v.addView(tv2)

        fun setP(p: Boolean) {
            v.setBackgroundColor(if (p) color else Color.TRANSPARENT)
            tv1.setTextColor(if (p) Color.BLACK else color)
            tv2.setTextColor(if (p) Color.BLACK else color)
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN   -> { setP(true);  GyroDriveAccessibilityService.instance?.pressButton(btn); true }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> { setP(false); GyroDriveAccessibilityService.instance?.releaseButton(btn); true }
                else -> false
            }
        }
        return v
    }

    private fun pressableView(
        w: Int, h: Int, isCircle: Boolean, color: Int,
        label: String, tSize: Float, btn: XCloudButton
    ): TextView {
        val tv = TextView(this).apply {
            text = label; textSize = tSize
            setTextColor(color); gravity = Gravity.CENTER
            setPadding(sh(0.5f), sh(0.5f), sh(0.5f), sh(0.5f))
            background = if (isCircle) strokeCircle(color) else strokeRoundRect(color, radius)
            layoutParams = LinearLayout.LayoutParams(w, h).also {
                it.setMargins(sh(0.4f), sh(0.4f), sh(0.4f), sh(0.4f))
            }
        }
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN   -> { tv.setBackgroundColor(color); tv.setTextColor(Color.BLACK); GyroDriveAccessibilityService.instance?.pressButton(btn); true }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> { tv.background = if (isCircle) strokeCircle(color) else strokeRoundRect(color, radius); tv.setTextColor(color); GyroDriveAccessibilityService.instance?.releaseButton(btn); true }
                else -> false
            }
        }
        return tv
    }

    // ── Drawables ─────────────────────────────────────────────────────────
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun strokeCircle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(sh(0.2f), color) }
    private fun roundRect(color: Int, r: Float) = GradientDrawable().apply { cornerRadius = r; setColor(color) }
    private fun strokeRoundRect(color: Int, r: Float, strokeW: Float = sh(0.18f).toFloat()) =
        GradientDrawable().apply { cornerRadius = r; setColor(Color.TRANSPARENT); setStroke(strokeW.toInt(), color) }

    // ── Layout helpers ────────────────────────────────────────────────────
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;  gravity = Gravity.CENTER_HORIZONTAL }
    private fun row()    = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun spacer(w: Int, h: Int) = View(this).apply { layoutParams = ViewGroup.LayoutParams(w, h) }

    private fun hudLP(w: Int, h: Int, g: Int, ml: Int = 0, mr: Int = 0, mt: Int = 0, mb: Int = 0) =
        FrameLayout.LayoutParams(w, h, g).also { it.setMargins(ml, mt, mr, mb) }

    private fun hudText(label: String, sz: Float, onClick: () -> Unit) =
        TextView(this).apply {
            text = label; textSize = sz
            setTextColor(0xCCFF8C00.toInt())
            setPadding(sh(1f), sh(0.5f), sh(1f), sh(0.5f))
            background = roundRect(0x22FF8C00.toInt(), radius)
            setOnClickListener { onClick() }
        }

    // ── Notification ─────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "GyroDrive Overlay", NotificationManager.IMPORTANCE_LOW
                ))
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("GyroDrive ativo")
        .setContentText("${ScreenMetrics.w}×${ScreenMetrics.h} — HUD rodando")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
}
