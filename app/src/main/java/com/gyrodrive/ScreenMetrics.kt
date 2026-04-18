package com.gyrodrive

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * Fonte única de verdade para as dimensões reais da tela.
 *
 * Todos os outros componentes chamam rx() / ry() para converter
 * coordenadas relativas (0.0–1.0) em pixels do dispositivo atual.
 *
 * Funciona em:
 *  • Qualquer resolução (720p, 1080p, 1440p, 4K, tablets)
 *  • Landscape e portrait
 *  • Dobráveis (refresh() ao mudar configuração)
 *  • Notch / punch-hole (usa a área física total, igual ao xCloud)
 */
object ScreenMetrics {

    var w: Int = 1920
        private set
    var h: Int = 1080
        private set

    fun init(ctx: Context) = refresh(ctx)

    fun refresh(ctx: Context) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = ctx.resources.displayMetrics
            w = dm.widthPixels; h = dm.heightPixels
        }
    }

    /** Fração horizontal → pixel real. Ex: rx(0.09f) numa tela 2400px = 216px */
    fun rx(ratio: Float): Float = ratio * w

    /** Fração vertical → pixel real. */
    fun ry(ratio: Float): Float = ratio * h

    /** Raio relativo → pixel real (usa a menor dimensão como base). */
    fun rr(ratio: Float): Float = ratio * minOf(w, h)
}
