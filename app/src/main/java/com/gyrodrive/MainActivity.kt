package com.gyrodrive

import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * Activity principal — tela de setup.
 *
 * Checklist que aparece na tela:
 *  [1] Permissão de overlay (SYSTEM_ALERT_WINDOW)
 *  [2] AccessibilityService ativo
 *  [3] Giroscópio disponível
 *  [4] Configurações (sensibilidade, calibração, mapa de coordenadas)
 *  [5] Botão INICIAR → abre Chrome no xCloud e ativa o overlay
 */
class MainActivity : AppCompatActivity() {

    // Prefs para persistir configurações do usuário
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        prefs = getSharedPreferences("gyrodrive", MODE_PRIVATE)
        setContentView(buildSetupScreen())
    }

    override fun onResume() {
        super.onResume()
        // Recarrega checklist ao voltar das configurações do sistema
        setContentView(buildSetupScreen())
    }

    // ── UI ────────────────────────────────────────────────────────────────
    private fun buildSetupScreen(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(0xFF060608.toInt())
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }

        // Logo
        content.addView(TextView(this).apply {
            text = "GYRODRIVE"
            textSize = 28f
            setTextColor(0xFFFF8C00.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.3f
        })
        content.addView(TextView(this).apply {
            text = "Forza xCloud Controller"
            textSize = 12f
            setTextColor(0x88FF8C00.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(32))
        })

        // ── Checklist ────────────────────────────────────────────────────
        content.addView(sectionTitle("SETUP"))

        // 1. Overlay permission
        val hasOverlay = Settings.canDrawOverlays(this)
        content.addView(checkRow(
            status = hasOverlay,
            label  = "Permissão de Overlay",
            detail = if (hasOverlay) "Concedida ✓" else "Necessária para o HUD flutuante"
        ) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        })

        // 2. Accessibility service
        val hasA11y = GyroDriveAccessibilityService.isActive()
        content.addView(checkRow(
            status = hasA11y,
            label  = "Serviço de Acessibilidade",
            detail = if (hasA11y) "GyroDrive Controller ativo ✓"
                     else         "Necessário para injetar toques no xCloud"
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            showA11yHint()
        })

        // 3. Gyro
        val gyroMgr = GyroManager(this)
        val hasGyro = gyroMgr.isAvailable
        content.addView(checkRow(
            status = hasGyro,
            label  = "Giroscópio",
            detail = if (hasGyro) "Sensor disponível ✓" else "Este dispositivo não tem giroscópio"
        ) { /* nada a fazer */ })

        // ── Configurações ─────────────────────────────────────────────────
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(24)) })
        content.addView(sectionTitle("CONFIGURAÇÕES"))

        // Sensibilidade
        content.addView(sliderRow(
            label   = "Sensibilidade de Direção",
            key     = "sensitivity",
            default = 80,
            min = 20, max = 150
        ))

        content.addView(sliderRow(
            label   = "Zona Morta (Deadzone)",
            key     = "deadzone",
            default = 8,
            min = 0, max = 30
        ))

        content.addView(sliderRow(
            label   = "Suavização (Anti-tremor)",
            key     = "smoothing",
            default = 25,
            min = 5, max = 90
        ))

        content.addView(sliderRow(
            label   = "Limite de Giro (° = 100%)",
            key     = "maxAngleDeg",
            default = 26,
            min = 10, max = 45
        ))

        // Coordenadas do xCloud
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(16)) })
        content.addView(actionBtn("🗺 Calibrar Coordenadas do xCloud", 0xFF22405A.toInt()) {
            showCoordCalibrator()
        })

        // ── Botão INICIAR ─────────────────────────────────────────────────
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(32)) })

        val allReady = hasOverlay && hasA11y && hasGyro
        content.addView(TextView(this).apply {
            text = if (allReady) "🚀  INICIAR CONTROLE" else "⚠  Complete o setup acima"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setTextColor(if (allReady) 0xFF000000.toInt() else 0x88FF8C00.toInt())
            background = if (allReady) {
                gradientDrawable(0xFFFF8C00.toInt(), 0xFFFF5500.toInt(), dp(14).toFloat())
            } else {
                strokeDrawable(0x44FF8C00.toInt(), dp(14).toFloat())
            }
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(12)) }
            if (allReady) setOnClickListener { startController() }
        })

        // Stop button (se já estiver rodando)
        content.addView(TextView(this).apply {
            text = "■  PARAR"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setTextColor(0xFFFF2B2B.toInt())
            background = strokeDrawable(0x44FF2B2B.toInt(), dp(14).toFloat())
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { stopController() }
        })

        root.addView(content)
        return root
    }

    // ── Controle do serviço ───────────────────────────────────────────────
    private fun startController() {
        applyPrefsToService()
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        })
        // Abre o xCloud no Chrome
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://xbox.com/play")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.android.chrome")
            })
        }.onFailure {
            // Chrome não instalado — abre no browser padrão
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://xbox.com/play")))
        }
        Toast.makeText(this, "GyroDrive ativo! Incline o celular para dirigir.", Toast.LENGTH_LONG).show()
    }

    private fun stopController() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })
        Toast.makeText(this, "GyroDrive parado.", Toast.LENGTH_SHORT).show()
    }

    private fun applyPrefsToService() {
        // Os valores são lidos pelo GyroManager dentro do OverlayService
        // via SharedPreferences. Esta função só garante que estejam salvos.
        val sensitivity  = prefs.getInt("sensitivity",  80) / 100f
        val deadzone     = prefs.getInt("deadzone",      8) / 100f
        val smoothing    = prefs.getInt("smoothing",    25) / 100f
        val maxAngleDeg  = prefs.getInt("maxAngleDeg",  26).toFloat()
        // Já foram salvos pelos sliders. Apenas garantindo.
    }

    // ── Calibrador de coordenadas ─────────────────────────────────────────
    private fun showCoordCalibrator() {
        AlertDialog.Builder(this)
            .setTitle("Calibrar Coordenadas xCloud")
            .setMessage("""
                Para calibrar manualmente:

                1. Abra o xCloud no Chrome
                2. Note as posições dos botões na tela
                3. Edite o arquivo XCloudTouchMap.kt com as coordenadas corretas

                Dica: Use 'adb shell getevent -l' ou ative o ponteiro do desenvolvedor para ver coordenadas de toque em tempo real.

                Resolução detectada: ${windowManager.currentWindowMetrics.bounds.width()} × ${windowManager.currentWindowMetrics.bounds.height()}
            """.trimIndent())
            .setPositiveButton("Entendido") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showA11yHint() {
        AlertDialog.Builder(this)
            .setTitle("Como ativar o Serviço")
            .setMessage("""
                Na tela de Acessibilidade que vai abrir:

                1. Procure por "GyroDrive Controller"
                2. Toque para abrir
                3. Ative o interruptor
                4. Confirme a permissão

                ⚠ Este serviço é necessário para injetar toques no xCloud. Sem ele, os botões do HUD não funcionarão.
            """.trimIndent())
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    // ── UI builders ───────────────────────────────────────────────────────
    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(0x88FF8C00.toInt())
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.25f
        setPadding(0, 0, 0, dp(8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun checkRow(
        status: Boolean, label: String, detail: String, onClick: () -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedRect(0x11FFFFFF, dp(10).toFloat())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 0, 0, dp(8)) }

        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also {
                it.setMargins(0, 0, dp(12), 0)
                it.gravity = Gravity.CENTER_VERTICAL
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (status) 0xFF00FF88.toInt() else 0xFFFF2B2B.toInt())
            }
        }
        addView(dot)

        val textGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textGroup.addView(TextView(context).apply {
            text = label; textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        })
        textGroup.addView(TextView(context).apply {
            text = detail; textSize = 10f
            setTextColor(if (status) 0x8800FF88.toInt() else 0x88FF8C00.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        })
        addView(textGroup)

        if (!status) {
            addView(TextView(context).apply {
                text = "CORRIGIR ›"; textSize = 10f
                setTextColor(0xCCFF8C00.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(10), dp(4), 0, dp(4))
                setOnClickListener { onClick() }
            })
        }
    }

    private fun sliderRow(label: String, key: String, default: Int, min: Int, max: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(12)) }

            val current = prefs.getInt(key, default)
            val tvHeader = TextView(context).apply {
                text = "$label: $current%"
                textSize = 11f
                setTextColor(0xCCFF8C00.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(4))
            }
            addView(tvHeader)

            val seekBar = android.widget.SeekBar(context).apply {
                this.min = min; this.max = max; progress = current
                progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, u: Boolean) {
                        tvHeader.text = "$label: $v%"
                        prefs.edit().putInt(key, v).apply()
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }
            addView(seekBar)
        }

    private fun actionBtn(label: String, bgColor: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label; textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(0xCCFFFFFF.toInt())
            background = roundedRect(bgColor, dp(10).toFloat())
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundedRect(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun strokeDrawable(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(1), color)
        }

    private fun gradientDrawable(colorStart: Int, colorEnd: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(colorStart, colorEnd)
        ).apply { cornerRadius = radius }
}
