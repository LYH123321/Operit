package com.ai.assistance.operit.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.File
import java.util.zip.ZipFile

/**
 * Live2D 悬浮窗服务：透明 WebView 渲染 Q 版 Live2D 立绘，可拖动、可说话气泡
 * 启动：context.startForegroundService 不需要，普通 startService 即可（仅展示悬浮窗）
 */
class Live2DService : Service() {

    companion object {
        @Volatile var webView: WebView? = null
        @Volatile var isShowing = false

        fun say(text: String) {
            webView?.post {
                webView?.evaluateJavascript("say(" + android.text.TextUtils.htmlEncode(text).let { "'" + it.replace("'", "\\'") + "'" } + ");", null)
            }
        }
    }

    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "show"
        when (action) {
            "hide" -> { hide(); stopSelf(); return START_NOT_STICKY }
            "say" -> { intent.getStringExtra("text")?.let { say(it) }; return START_NOT_STICKY }
        }
        if (isShowing) return START_NOT_STICKY
        if (!android.provider.Settings.canDrawOverlays(this)) return START_NOT_STICKY

        // 解压 web.zip 到 filesDir/live2d（首次）
        val dir = File(filesDir, "live2d")
        val index = File(dir, "index.html")
        if (!index.exists()) {
            try {
                dir.mkdirs()
                ZipFile(extractFromAssets()).use { z ->
                    val entries = z.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val out = File(dir, e.name)
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            z.getInputStream(e).use { input ->
                                out.outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Live2D", "unzip fail", e)
                return START_NOT_STICKY
            }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.webChromeClient = WebChromeClient()
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.loadUrl("file://" + index.absolutePath)

        val dm = resources.displayMetrics
        val size = (Math.min(dm.widthPixels, dm.heightPixels) * 0.38f).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0; y = 0
        }

        // 拖动
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        wv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y; moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    params.x = startX - dx; params.y = startY - dy
                    windowManager?.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(wv, params)
            webView = wv
            isShowing = true
        } catch (e: Exception) {
            android.util.Log.e("Live2D", "addView fail", e)
        }
        return START_NOT_STICKY
    }

    private fun extractFromAssets(): File {
        // 从 assets 拷到 cache 再解压
        val f = File(cacheDir, "live2d_web.zip")
        if (!f.exists() || f.length() == 0L) {
            assets.open("live2d/web.zip").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        return f
    }

    private fun hide() {
        try {
            webView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        webView = null
        isShowing = false
    }

    override fun onDestroy() { hide() }
}
