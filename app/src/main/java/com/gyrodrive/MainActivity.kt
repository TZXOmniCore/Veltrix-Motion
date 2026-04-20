package com.veltrixmotion

import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs:    SharedPreferences
    private lateinit var profiles: ProfileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = 0xFF060608.toInt()
        window.navigationBarColor = 0xFF060608.toInt()
        prefs    = getSharedPreferences("veltrix", MODE_PRIVATE)
        profiles = ProfileManager(this)
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF060608.toInt())
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        col.addView(buildHero())
        col.addView(buildStatus())
        col.addView(buildProfiles())
        col.addView(buildSettings())
        col.addView(buildActions())
        col.addView(buildFooter())

        scroll.addView(col)
        return scroll
    }

    // ── HERO ─────────────────────────────────────────────────────────────
    private fun buildHero(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity     = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(48), 0, dp(28))
        setBackgroundColor(0xFF060608.toInt())

        addView(tv("🎮", 52f, 0xFFFFFFFF.toInt()).apply { gravity = Gravity.CENTER })
        addView(tv("VELTRIX MOTION", 22f, 0xFFFF8C00.toInt()).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER; letterSpacing = 0.3f
            setPadding(0, dp(8), 0, 0)
        })
        addView(tv("Xbox Cloud Gaming Controller", 12f, 0x55FFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(badge("v5.0", 0xFF00FF88.toInt(), 0x1500FF88))
            addView(space(dp(8))); addView(badge("STABLE", 0xFF00C8FF.toInt(), 0x1500C8FF))
            addView(space(dp(8))); addView(badge("1GB RAM+", 0xFFFF8C00.toInt(), 0x15FF8C00))
        })
    }

    // ── STATUS ────────────────────────────────────────────────────────────
    private fun buildStatus(): LinearLayout {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y    = GyroDriveAccessibilityService.isActive()
        val hasGyro    = GyroManager(this).isAvailable

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), 0)

            addView(sectionLabel("SETUP"))
            addView(space(dp(10)))

            addView(statusCard(hasOverlay, "Permissão de Overlay",
                if (hasOverlay) "Concedida ✓" else "Toque para conceder") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            })

            addView(statusCard(hasA11y, "Serviço de Acessibilidade",
                if (hasA11y) "Veltrix Motion Controller ativo ✓" else "Toque para ativar") {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Como ativar")
                    .setMessage("1. Procure \"Veltrix Motion Controller\"\n2. Toque e ative\n3. Confirme\n4. Volte aqui")
                    .setPositiveButton("Abrir") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .setNegativeButton("Cancelar", null).show()
            })

            addView(statusCard(hasGyro, "Giroscópio",
                if (hasGyro) "Sensor disponível ✓" else "Sem giroscópio (use o stick do HUD)") {})

            // Barra de progresso
            addView(space(dp(12)))
            val done = listOf(hasOverlay, hasA11y, hasGyro).count { it }
            addView(FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(4))
                background   = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x22FFFFFF) }
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(0xFFFF8C00.toInt(), 0xFF00FF88.toInt())
                    ).apply { cornerRadius = dp(2).toFloat() }
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    scaleX = done / 3f; pivotX = 0f
                })
            })
            addView(tv("$done de 3 itens prontos", 10f,
                if (done == 3) 0xFF00FF88.toInt() else 0x66FF8C00.toInt()).apply {
                typeface = Typeface.MONOSPACE; setPadding(0, dp(6), 0, 0)
            })
        }
    }

    // ── PERFIS ────────────────────────────────────────────────────────────
    private fun buildProfiles(): LinearLayout {
        val activeId = profiles.getActiveProfileId()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), 0)

            addView(sectionLabel("PERFIS DE CONTROLE"))
            addView(space(dp(10)))

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            }

            profiles.getAllProfiles().forEach { profile ->
                val isActive = profile.id == activeId
                row.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity     = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also {
                        it.setMargins(dp(4), 0, dp(4), 0)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (isActive) 0x22FF8C00.toInt() else 0x0AFFFFFF)
                        setStroke(dp(1), if (isActive) 0x66FF8C00.toInt() else 0x11FFFFFF)
                    }
                    addView(tv(profile.emoji, 26f, 0xFFFFFFFF.toInt()).apply { gravity = Gravity.CENTER })
                    addView(tv(profile.name, 10f,
                        if (isActive) 0xFFFF8C00.toInt() else 0x88FFFFFF.toInt()).apply {
                        typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                        setPadding(0, dp(4), 0, 0)
                    })
                    if (isActive) addView(tv("ATIVO", 9f, 0xFF00FF88.toInt()).apply {
                        typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                        setPadding(0, dp(2), 0, 0)
                    })
                    setOnClickListener {
                        profiles.setActiveProfile(profile.id)
                        setContentView(buildScreen())
                    }
                })
            }
            addView(row)

            // Botão de calibração
            addView(space(dp(12)))
            addView(tv("⚙  CALIBRAR BOTÕES DO XCLOUD", 11f, 0xFF00C8FF.toInt()).apply {
                typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(0x0A00C8FF); setStroke(1, 0x3300C8FF)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                setOnClickListener { startCalibration() }
            })
        }
    }

    // ── CONFIGURAÇÕES ─────────────────────────────────────────────────────
    private fun buildSettings(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), 0)

            addView(sectionLabel("AJUSTE FINO"))
            addView(space(dp(10)))

            val profile = profiles.getActiveProfile()
            val card    = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background  = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat(); setColor(0x0AFFFFFF); setStroke(1, 0x11FFFFFF)
                }
                setPadding(dp(16), dp(16), dp(16), dp(8))
            }

            listOf(
                Triple("Sensibilidade",   "sensitivity",  (profile.sensitivity * 100).toInt()),
                Triple("Zona Morta",      "deadzone",     (profile.deadzone * 100).toInt()),
                Triple("Suavização",      "smoothing",    (profile.smoothing * 100).toInt()),
                Triple("Limite de Giro°", "maxAngleDeg",  profile.maxAngleDeg.toInt()),
                Triple("Curva Central",   "curveCenter",  (profile.curveCenter * 100).toInt()),
                Triple("Força Extrema",   "curveEdge",    (profile.curveEdge * 100).toInt())
            ).forEach { (label, key, defVal) ->
                val tvLb = tv("$label:  $defVal", 11f, 0xCCFF8C00.toInt()).apply {
                    typeface = Typeface.MONOSPACE
                }
                card.addView(tvLb)
                card.addView(SeekBar(this@MainActivity).apply {
                    min = 0; max = 200; progress = defVal
                    progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
                    thumbTintList    = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also {
                        it.setMargins(0, dp(4), 0, dp(14))
                    }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                            tvLb.text = "$label:  $v"
                            prefs.edit().putInt("${profiles.getActiveProfileId()}_$key", v).apply()
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                })
            }
            addView(card)
        }
    }

    // ── BOTÕES DE AÇÃO ────────────────────────────────────────────────────
    private fun buildActions(): LinearLayout {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y    = GyroDriveAccessibilityService.isActive()
        val hasGyro    = GyroManager(this).isAvailable
        val allReady   = hasOverlay && hasA11y

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), 0)

            addView(tv(
                if (allReady) "▶   INICIAR CONTROLE" else "⚠   Complete o setup",
                16f,
                if (allReady) 0xFF000000.toInt() else 0x44FF8C00.toInt()
            ).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER; letterSpacing = 0.1f
                setPadding(dp(24), dp(18), dp(24), dp(18))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                background = if (allReady) GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFFFF8C00.toInt(), 0xFFFF5500.toInt())
                ).apply { cornerRadius = dp(14).toFloat() }
                else GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x0AFF8C00); setStroke(1, 0x33FF8C00) }
                if (allReady) setOnClickListener { startController() }
            })

            addView(space(dp(12)))

            addView(tv("■   PARAR CONTROLE", 14f, 0xAAFF2B2B.toInt()).apply {
                typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; letterSpacing = 0.1f
                setPadding(dp(24), dp(16), dp(24), dp(16))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0); setStroke(1, 0x44FF2B2B) }
                setOnClickListener { stopController() }
            })
        }
    }

    // ── FOOTER ────────────────────────────────────────────────────────────
    private fun buildFooter() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(40), dp(20), dp(48))
        addView(View(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1); setBackgroundColor(0x11FFFFFF)
        })
        addView(space(dp(20)))
        addView(tv("VELTRIX MOTION", 10f, 0x33FF8C00.toInt()).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER; letterSpacing = 0.3f
        })
        addView(tv("Universal · 1GB RAM+ · D-pad · Freio de Mão · Câmera Trava", 9f, 0x22FFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        })
    }

    // ── Controle ──────────────────────────────────────────────────────────
    private fun startController() {
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_START })
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.xbox.com/play")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK; setPackage("com.android.chrome")
            })
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.xbox.com/play")))
        }
        Toast.makeText(this, "Veltrix Motion ativo! Incline para dirigir.", Toast.LENGTH_LONG).show()
    }

    private fun stopController() {
        GyroDriveAccessibilityService.instance?.deactivateController()
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
        Toast.makeText(this, "Parado.", Toast.LENGTH_SHORT).show()
    }

    private fun startCalibration() {
        if (!GyroDriveAccessibilityService.isActive()) {
            Toast.makeText(this, "Ative o Serviço de Acessibilidade primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_CALIBRATE })
        Toast.makeText(this, "Abrindo calibração... Vá para o xCloud e siga as instruções.", Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.xbox.com/play")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK; setPackage("com.android.chrome")
            })
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private fun badge(text: String, color: Int, bg: Int) = tv("  $text  ", 9f, color).apply {
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat(); setColor(bg); setStroke(1, color and 0x44FFFFFF.toInt())
        }
        setPadding(dp(6), dp(3), dp(6), dp(3))
    }

    private fun statusCard(ok: Boolean, title: String, detail: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.setMargins(0,0,0,dp(8)) }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (ok) 0x0A00FF88 else 0x0AFF8C00)
                setStroke(1, if (ok) 0x2200FF88 else 0x22FF8C00)
            }
            if (!ok) setOnClickListener { onClick() }
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also {
                    it.setMargins(0,0,dp(14),0); it.gravity = Gravity.CENTER_VERTICAL
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (ok) 0xFF00FF88.toInt() else 0xFFFF8C00.toInt())
                }
            })
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            texts.addView(tv(title, 13f, 0xEEFFFFFF.toInt()).apply { typeface = Typeface.MONOSPACE })
            texts.addView(tv(detail, 10f, if (ok) 0x8800FF88.toInt() else 0x88FF8C00.toInt()).apply {
                typeface = Typeface.MONOSPACE; setPadding(0, dp(3), 0, 0)
            })
            addView(texts)
            if (!ok) addView(tv("›", 22f, 0x55FF8C00.toInt()).apply { setPadding(dp(8),0,0,0) })
        }

    private fun sectionLabel(text: String) = tv(text, 10f, 0x55FF8C00.toInt()).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.25f
    }

    private fun tv(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }

    private fun space(h: Int) = View(this).apply { layoutParams = ViewGroup.LayoutParams(MATCH, h) }
    private fun dp(v: Int)    = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
