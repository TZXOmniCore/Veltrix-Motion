package com.veltrixmotion

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*

/**
 * Calibração visual — mostra um overlay semitransparente sobre o xCloud
 * e pede ao usuário para tocar em cada botão na tela.
 *
 * Resultado: um XCloudTouchMap com coordenadas reais do dispositivo,
 * convertidas em ratios universais.
 */
class CalibrationManager(
    private val context: Context,
    private val wm: WindowManager,
    private val onComplete: (XCloudTouchMap) -> Unit,
    private val onCancel: () -> Unit
) {

    // Sequência de botões a calibrar
    private val sequence = listOf(
        CalibStep(XCloudButton.RT,         "Trigger DIREITO\n(Acelerar)"),
        CalibStep(XCloudButton.LT,         "Trigger ESQUERDO\n(Freio)"),
        CalibStep(XCloudButton.RB,         "Bumper DIREITO (RB)"),
        CalibStep(XCloudButton.LB,         "Bumper ESQUERDO (LB)"),
        CalibStep(XCloudButton.A,          "Botão  A  (verde)"),
        CalibStep(XCloudButton.B,          "Botão  B  (vermelho)"),
        CalibStep(XCloudButton.X,          "Botão  X  (azul)"),
        CalibStep(XCloudButton.Y,          "Botão  Y  (amarelo)"),
        CalibStep(XCloudButton.VIEW,       "Botão VIEW (esquerdo centro)"),
        CalibStep(XCloudButton.MENU,       "Botão MENU (direito centro)"),
        CalibStep(XCloudButton.DPAD_UP,    "D-pad  ↑"),
        CalibStep(XCloudButton.DPAD_DOWN,  "D-pad  ↓"),
        CalibStep(XCloudButton.DPAD_LEFT,  "D-pad  ←"),
        CalibStep(XCloudButton.DPAD_RIGHT, "D-pad  →"),
        // Analógico esquerdo — toca no centro
        CalibStep(null, "Centro do Analógico ESQUERDO", isLeftStick  = true),
        // Analógico direito — toca no centro
        CalibStep(null, "Centro do Analógico DIREITO",  isRightStick = true),
    )

    private var currentStep = 0
    private val calibrated  = mutableMapOf<XCloudButton, TouchRatio>()
    private var leftStickX  = 0.092f; private var leftStickY  = 0.760f
    private var rightStickX = 0.625f; private var rightStickY = 0.760f

    private var overlayView: View? = null

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var tvStep:     TextView
    private lateinit var tvLabel:    TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnSkip:    TextView
    private lateinit var btnCancel:  TextView

    fun start() {
        showCalibOverlay()
    }

    private fun showCalibOverlay() {
        val root = buildOverlay()
        overlayView = root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        wm.addView(root, params)
        updateStep()
    }

    private fun buildOverlay(): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(0xAA000000.toInt())
        }

        // Painel central de instrução
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            background  = roundRect(0xEE0A0A12.toInt(), dp(16).toFloat())
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).also { it.topMargin = dp(16) }
        }

        tvProgress = TextView(context).apply {
            textSize = 10f; setTextColor(0x88FF8C00.toInt())
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        panel.addView(tvProgress)

        tvStep = TextView(context).apply {
            text = "CALIBRAÇÃO"; textSize = 13f
            setTextColor(0xFFFF8C00.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER; letterSpacing = 0.2f
            setPadding(0, dp(4), 0, dp(12))
        }
        panel.addView(tvStep)

        tvLabel = TextView(context).apply {
            textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        panel.addView(tvLabel)

        val hint = TextView(context).apply {
            text = "Toque no botão indicado no xCloud →"
            textSize = 11f; setTextColor(0x88FFFFFF.toInt())
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(16))
        }
        panel.addView(hint)

        // Botões Skip e Cancel
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }

        btnSkip = TextView(context).apply {
            text = "PULAR ›"; textSize = 11f
            setTextColor(0x88FF8C00.toInt()); typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = roundRect(0x22FF8C00.toInt(), dp(8).toFloat())
            setOnClickListener { skipStep() }
        }
        btnRow.addView(btnSkip)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), 1)
        }
        btnRow.addView(spacer)

        btnCancel = TextView(context).apply {
            text = "✕ CANCELAR"; textSize = 11f
            setTextColor(0x88FF2B2B.toInt()); typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = roundRect(0x22FF2B2B.toInt(), dp(8).toFloat())
            setOnClickListener { cancel() }
        }
        btnRow.addView(btnCancel)
        panel.addView(btnRow)

        root.addView(panel)

        // Intercepta toque na área fora do painel = toque no botão do xCloud
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val px = e.rawX; val py = e.rawY
                recordTouch(px, py)
            }
            false // passa o toque para o xCloud por baixo
        }

        return root
    }

    private fun updateStep() {
        val step = sequence.getOrNull(currentStep) ?: return
        tvProgress.text = "Passo ${currentStep + 1} de ${sequence.size}"
        tvLabel.text    = step.label
    }

    private fun recordTouch(rawX: Float, rawY: Float) {
        val step = sequence.getOrNull(currentStep) ?: return
        val rx = rawX / ScreenMetrics.w
        val ry = rawY / ScreenMetrics.h

        when {
            step.isLeftStick  -> { leftStickX  = rx; leftStickY  = ry }
            step.isRightStick -> { rightStickX = rx; rightStickY = ry }
            step.button != null -> calibrated[step.button] = TouchRatio(rx, ry)
        }

        currentStep++
        if (currentStep >= sequence.size) {
            finish()
        } else {
            updateStep()
        }
    }

    private fun skipStep() {
        // Usa valor default para o passo pulado
        val step = sequence.getOrNull(currentStep) ?: return
        if (step.button != null) {
            val default = XCloudTouchMap.default()
            default.coordFor(step.button)?.let { calibrated[step.button] = it }
        }
        currentStep++
        if (currentStep >= sequence.size) finish() else updateStep()
    }

    private fun finish() {
        dismiss()
        val default = XCloudTouchMap.default()
        val allButtons = XCloudButton.values().associateWith { btn ->
            calibrated[btn] ?: default.coordFor(btn) ?: TouchRatio(0.5f, 0.5f)
        }
        onComplete(XCloudTouchMap(
            leftStickCX      = leftStickX,
            leftStickCY      = leftStickY,
            leftStickRadius  = 0.110f,
            rightStickCX     = rightStickX,
            rightStickCY     = rightStickY,
            rightStickRadius = 0.090f,
            buttons          = allButtons
        ))
    }

    private fun cancel() {
        dismiss()
        onCancel()
    }

    private fun dismiss() {
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
    }

    private fun roundRect(color: Int, r: Float) =
        android.graphics.drawable.GradientDrawable().apply { cornerRadius = r; setColor(color) }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    data class CalibStep(
        val button:       XCloudButton?,
        val label:        String,
        val isLeftStick:  Boolean = false,
        val isRightStick: Boolean = false
    )
}
