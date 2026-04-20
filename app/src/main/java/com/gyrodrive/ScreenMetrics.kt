package com.veltrixmotion

import android.content.Context
import android.os.Build
import android.view.WindowManager

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

    fun rx(ratio: Float): Float = ratio * w
    fun ry(ratio: Float): Float = ratio * h
    fun rr(ratio: Float): Float = ratio * minOf(w, h)
}
