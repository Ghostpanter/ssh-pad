package com.sshtab.pad.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.sshtab.pad.MainActivity
import com.sshtab.pad.log.SessionLog

/**
 * 1 个不抢焦点的悬浮球。国行把「有悬浮窗」的应用当前台，不容易冷冻。
 */
class KeepAliveFloat(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null

    fun show() {
        if (view != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            SessionLog.event("overlay permission missing")
            return
        }
        try {
            val tv = TextView(context).apply {
                text = "SSH"
                textSize = 12f
                setPadding(22, 14, 22, 14)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC0F766E.toInt())
                setOnClickListener {
                    val i = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(i)
                }
            }
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.END or Gravity.TOP
            lp.x = 12
            lp.y = 180
            wm.addView(tv, lp)
            view = tv
            SessionLog.event("overlay shown")
        } catch (t: Throwable) {
            SessionLog.event("overlay failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Throwable) {}
    }
}
